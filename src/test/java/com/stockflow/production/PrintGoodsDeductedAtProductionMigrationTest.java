package com.stockflow.production;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V20261022000200 (G4-Q2 / PACK-PRINT) on a database holding printed goods handed to packing under the old model -
 * HELD for their order in a PACKING area, the hand-over MOVE in the ledger - which the new packing would never deduct.
 * The in-flight order's holds are consumed exactly as the completion now does it (one CONSUMPTION row naming the
 * production order, on_hand and reserved down by the hold); a cancelled order's goods, goods already asked to be
 * scrapped, and free stock an order holds in packing are left as they are. Then a cancelled order's consumed goods may
 * come back from nowhere for their scrap (PUT_BACK, reference ORDER), and nothing else may.
 */
class PrintGoodsDeductedAtProductionMigrationTest {

    private static final String BEFORE = "20261022000100";
    private static final String HCM = "md5('demo:wh:HCM')::uuid";
    private static final String EDITOR = "md5('demo:user:editor')::uuid";
    private static final String SKU = "SOFA-3S-GREY";
    private static final String PACKING = "HCM-PACK01";

    private static Flyway flyway(PostgreSQLContainer<?> pg, String target) {
        var config = Flyway.configure().dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
                .locations("classpath:db/migration", "classpath:db/demo");
        return (target == null ? config : config.target(target)).load();
    }

    private static List<String> rows(Statement sql, String query) throws Exception {
        List<String> out = new ArrayList<>();
        try (ResultSet rs = sql.executeQuery(query)) {
            int columns = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                StringBuilder row = new StringBuilder();
                for (int i = 1; i <= columns; i++) {
                    row.append(i > 1 ? "|" : "").append(rs.getString(i));
                }
                out.add(row.toString());
            }
        }
        return out;
    }

    /** An order of {@code status} with one line, and a HELD hold of {@code quantity} on the packing-area layer. */
    private static void orderHolding(Statement sql, String key, String status, int quantity) throws Exception {
        sql.executeUpdate("""
                INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount,
                    currency, placed_at, created_at, warehouse_id, released_at, released_by, cancellation_reason_code,
                    cancellation_reason)
                VALUES (md5('g4q2:o:%1$s')::uuid, 'SO-G4Q2-%1$s', 'c0000000-0000-4000-8000-000000000001',
                        gen_random_uuid(), '%2$s', 2000, 'VND', NOW(), NOW(), %3$s, NOW(), %6$s,
                        CASE WHEN '%2$s' = 'CANCELLED' THEN 'CUSTOMER_REQUEST' END,
                        CASE WHEN '%2$s' = 'CANCELLED' THEN 'CUSTOMER_REQUEST' END);
                INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency)
                VALUES (md5('g4q2:l:%1$s')::uuid, md5('g4q2:o:%1$s')::uuid, '%4$s', %5$d, 1000, 'VND');
                INSERT INTO inventory.stock_reservation (id, stock_item_id, order_id, root_request_id, request_id,
                    quantity, reserved_at, expires_at, status)
                VALUES (md5('g4q2:r:%1$s')::uuid, md5('g4q2:si')::uuid, md5('g4q2:o:%1$s')::uuid, gen_random_uuid(),
                        gen_random_uuid(), %5$d, NOW(), NULL, 'HELD');
                INSERT INTO ordering.order_line_reservation (order_line_id, reservation_id)
                VALUES (md5('g4q2:l:%1$s')::uuid, md5('g4q2:r:%1$s')::uuid)"""
                .formatted(key, status, HCM, SKU, quantity, EDITOR));
    }

    /** The hand-over line production wrote for {@code key}'s hold: print shop to packing, IN_PRODUCTION → AVAILABLE. */
    private static void handedOver(Statement sql, String key, int quantity) throws Exception {
        sql.executeUpdate("""
                INSERT INTO inventory.stock_movement (id, movement_type, sku, from_location_code, to_location_code,
                    quantity, from_status, to_status, reference_type, reference_id, reason, actor_id, occurred_at)
                VALUES (gen_random_uuid(), 'MOVE', '%s', 'HCM-PRD01', '%s', %d, 'IN_PRODUCTION', 'AVAILABLE',
                        'PRODUCTION_ORDER', md5('g4q2:po:%s')::uuid, 'Hold ' || md5('g4q2:r:%s')::uuid, %s,
                        NOW() - INTERVAL '1 hour')""".formatted(SKU, PACKING, quantity, key, key, EDITOR));
    }

    @Test
    void handedOverGoodsOfALiveOrderAreConsumedTheRestLeftAsTheyAre() throws Exception {
        try (var pg = new PostgreSQLContainer<>("postgres:16-alpine")) {
            pg.start();
            flyway(pg, BEFORE).migrate();
            try (Connection connection = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
                    Statement sql = connection.createStatement()) {
                // One packing-area layer: 3 in flight + 2 cancelled + 2 asked to scrap + 1 free stock held, 2 unheld.
                sql.executeUpdate("""
                        INSERT INTO inventory.stock_item (id, sku, location_code, on_hand, reserved, status, version,
                            created_at, received_at)
                        VALUES (md5('g4q2:si')::uuid, '%s', '%s', 10, 8, 'AVAILABLE', 0, NOW(),
                                NOW() - INTERVAL '30 days')""".formatted(SKU, PACKING));
                orderHolding(sql, "live", "READY_TO_FULFILL", 3);
                handedOver(sql, "live", 3);
                orderHolding(sql, "cancelled", "CANCELLED", 2);
                handedOver(sql, "cancelled", 2);
                orderHolding(sql, "closed", "PARTIALLY_FULFILLED", 2);
                handedOver(sql, "closed", 2);
                sql.executeUpdate("""
                        INSERT INTO inventory.stock_adjustment (id, adjustment_number, location_code, sku, quantity_delta,
                            reason_code, note, status, requested_by, reservation_id, received_at)
                        SELECT gen_random_uuid(), 'ADJ-G4Q2-1', '%s', '%s', -2, 'SCRAP', 'rest closed',
                               'PENDING_APPROVAL', %s, md5('g4q2:r:closed')::uuid, received_at
                          FROM inventory.stock_item WHERE id = md5('g4q2:si')::uuid""".formatted(PACKING, SKU, EDITOR));
                orderHolding(sql, "free", "PICKING", 1);   // free stock in packing: no hand-over line

                assertThat(flyway(pg, null).migrate().migrationsExecuted).isPositive();

                assertThat(rows(sql, """
                        SELECT o.order_number, r.status, r.closed_at IS NOT NULL
                          FROM inventory.stock_reservation r JOIN ordering.customer_order o ON o.id = r.order_id
                         WHERE r.stock_item_id = md5('g4q2:si')::uuid ORDER BY o.order_number"""))
                        .containsExactly("SO-G4Q2-cancelled|HELD|f", "SO-G4Q2-closed|HELD|f",
                                "SO-G4Q2-free|HELD|f", "SO-G4Q2-live|CONSUMED|t");
                assertThat(rows(sql, "SELECT on_hand, reserved, version FROM inventory.stock_item "
                        + "WHERE id = md5('g4q2:si')::uuid"))
                        .as("the in-flight 3 left stock, once").containsExactly("7|5|1");
                assertThat(rows(sql, """
                        SELECT movement_type, sku, from_location_code, to_location_code IS NULL, quantity, from_status,
                               reference_type, reference_id = md5('g4q2:po:live')::uuid,
                               reason = 'Hold ' || md5('g4q2:r:live')::uuid, actor_id = %s
                          FROM inventory.stock_movement WHERE movement_type = 'CONSUMPTION'""".formatted(EDITOR)))
                        .containsExactly("CONSUMPTION|%s|%s|t|3|AVAILABLE|PRODUCTION_ORDER|t|t|t"
                                .formatted(SKU, PACKING));
                assertThat(rows(sql, "SELECT to_regclass('g4q2_finished') IS NULL")).containsExactly("t");

                // BR-10 after G4-Q2: consumed goods may come back from nowhere for their scrap - with an ORDER only.
                sql.executeUpdate("""
                        INSERT INTO inventory.stock_movement (id, movement_type, sku, to_location_code, quantity,
                            to_status, reference_type, reference_id, reason, occurred_at)
                        VALUES (gen_random_uuid(), 'PUT_BACK', '%s', '%s', 1, 'AVAILABLE', 'ORDER',
                                md5('g4q2:o:live')::uuid, 'Hold x', NOW())""".formatted(SKU, PACKING));
                assertThat(rows(sql, """
                        SELECT count(*) FROM inventory.stock_movement WHERE movement_type = 'PUT_BACK'
                           AND reference_type = 'ORDER'""")).containsExactly("1");
                org.assertj.core.api.Assertions.assertThatThrownBy(() -> sql.executeUpdate("""
                        INSERT INTO inventory.stock_movement (id, movement_type, sku, to_location_code, quantity,
                            to_status, reference_type, reference_id, occurred_at)
                        VALUES (gen_random_uuid(), 'PUT_BACK', '%s', '%s', 1, 'AVAILABLE', 'STOCK_ADJUSTMENT',
                                gen_random_uuid(), NOW())""".formatted(SKU, PACKING)))
                        .hasMessageContaining("ck_stock_movement_shape");
            }
        }
    }
}
