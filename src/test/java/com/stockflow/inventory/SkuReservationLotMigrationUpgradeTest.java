package com.stockflow.inventory;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V20261019000800 (SCRUM-152) and V20261019000900 (SCRUM-142/153) on a database that already holds data: a
 * checkout hold placed on a stock item before SKU reservations existed, its order, and a pick of it in the ledger.
 * The upgrade must keep that hold exactly as it is (still pickable where it is), give every row its SKU, tag the
 * stock layer and the ledger line with a lot and the pick with its order, and from then on enforce the new rules.
 */
class SkuReservationLotMigrationUpgradeTest {

    /** The newest migration before this change. */
    private static final String BEFORE = "20261019000700";
    private static final String SKU = "SOFA-3S-GREY";
    private static final String BIN = "HCM-A01-1-A";

    @Test
    void legacyHoldsSurviveAndLotsAreBackfilled() throws Exception {
        try (var pg = new PostgreSQLContainer<>("postgres:16-alpine")) {
            pg.start();
            flyway(pg, BEFORE).migrate();
            try (Connection db = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())) {
                UUID order = UUID.randomUUID();
                UUID item = UUID.randomUUID();
                UUID hold = UUID.randomUUID();
                update(db, """
                        INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status,
                            total_amount, currency, placed_at, version, created_at)
                        VALUES (?, 'SO-20991231-000001', 'c0000000-0000-4000-8000-000000000001', gen_random_uuid(),
                                'PENDING_PAYMENT', 1000, 'VND', NOW(), 0, NOW())""", order);
                update(db, """
                        INSERT INTO inventory.stock_item (id, sku, location_code, on_hand, reserved, status, received_at,
                            version, created_at)
                        VALUES (?, ?, ?, 10, 3, 'AVAILABLE', NOW() - interval '10 days', 0, NOW())""", item, SKU, BIN);
                update(db, """
                        INSERT INTO inventory.stock_reservation (id, stock_item_id, order_id, root_request_id, request_id,
                            quantity, reserved_at, expires_at, status)
                        VALUES (?, ?, ?, gen_random_uuid(), gen_random_uuid(), 3, NOW(), NULL, 'HELD')""",
                        hold, item, order);
                update(db, """
                        INSERT INTO inventory.stock_movement (id, movement_type, sku, from_location_code, quantity,
                            reference_type, reference_id, occurred_at)
                        VALUES (gen_random_uuid(), 'PICK', ?, ?, 1, 'ORDER', ?, NOW())""", SKU, BIN, order);

                var result = flyway(pg, null).migrate();
                assertThat(result.success).isTrue();
                assertThat(result.migrations).extracting(m -> m.version)
                        .contains("20261019000800", "20261019000900", "20261019001000");

                // The pre-split checkout hold stays on its stock item, as it was; it now names its SKU.
                assertThat(text(db, """
                        SELECT sku || ':' || status || ':' || stock_item_id || ':' || (allocated_from_id IS NULL)
                          FROM inventory.stock_reservation WHERE id = ?""", hold))
                        .isEqualTo(SKU + ":HELD:" + item + ":true");
                // The layer belongs to an UNTRACED lot of its identity; the pick names that lot and its order.
                String lot = text(db, "SELECT lot_id::text FROM inventory.stock_item WHERE id = ?", item);
                assertThat(lot).isNotNull();
                assertThat(text(db, "SELECT origin_type || ':' || sku FROM inventory.stock_lot WHERE id = ?::uuid", lot))
                        .isEqualTo("UNTRACED:" + SKU);
                assertThat(text(db, """
                        SELECT lot_id::text || ':' || order_id FROM inventory.stock_movement
                         WHERE reference_id = ? AND movement_type = 'PICK'""", order)).isEqualTo(lot + ":" + order);
                assertThat(number(db, "SELECT count(*) FROM inventory.stock_item WHERE lot_id IS NULL")).isZero();
                assertThat(number(db, "SELECT count(*) FROM inventory.stock_reservation WHERE sku IS NULL")).isZero();

                // From now on: a new layer gets a lot by itself; a SKU reservation must be covered by stock.
                UUID fresh = UUID.randomUUID();
                update(db, """
                        INSERT INTO inventory.stock_item (id, sku, location_code, on_hand, reserved, status, received_at,
                            version, created_at)
                        VALUES (?, ?, 'HCM-A01-1-B', 1, 0, 'AVAILABLE', NOW() - interval '10 days', 0, NOW())""", fresh, SKU);
                assertThat(text(db, "SELECT lot_id::text FROM inventory.stock_item WHERE id = ?", fresh)).isNotNull();
                update(db, """
                        INSERT INTO inventory.stock_reservation (id, sku, order_id, root_request_id, request_id, quantity,
                            reserved_at, expires_at, status)
                        VALUES (gen_random_uuid(), ?, ?, gen_random_uuid(), gen_random_uuid(), 1, NOW(),
                                NOW() + interval '30 minutes', 'HELD')""", SKU, order);
                assertThatThrownBy(() -> update(db, """
                        INSERT INTO inventory.stock_reservation (id, sku, order_id, root_request_id, request_id, quantity,
                            reserved_at, expires_at, status)
                        VALUES (gen_random_uuid(), ?, ?, gen_random_uuid(), gen_random_uuid(), 100000, NOW(),
                                NOW() + interval '30 minutes', 'HELD')""", SKU, order))
                        .isInstanceOf(SQLException.class).hasMessageContaining("exceed");
                assertThatThrownBy(() -> update(db, "UPDATE inventory.stock_movement SET quantity = 2 WHERE reference_id = ?",
                        order)).as("the ledger is append-only again").isInstanceOf(SQLException.class);
            }
        }
    }

    private static Flyway flyway(PostgreSQLContainer<?> pg, String target) {
        var config = Flyway.configure().dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
                .locations("classpath:db/migration", "classpath:db/demo");
        if (target != null) {
            config.target(target);
        }
        return config.load();
    }

    private static void update(Connection db, String sql, Object... args) throws SQLException {
        try (PreparedStatement statement = db.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            statement.executeUpdate();
        }
    }

    private static String text(Connection db, String sql, Object... args) throws SQLException {
        try (PreparedStatement statement = db.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }

    private static long number(Connection db, String sql) throws SQLException {
        try (PreparedStatement statement = db.prepareStatement(sql); ResultSet rows = statement.executeQuery()) {
            rows.next();
            return rows.getLong(1);
        }
    }
}
