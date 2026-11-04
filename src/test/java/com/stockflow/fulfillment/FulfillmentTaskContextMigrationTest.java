package com.stockflow.fulfillment;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V20261014000100 (SCRUM-466) on a database that already holds pick rows: tasks written before they
 * carried an order number and a warehouse get both from their order, or from where the order's holds are,
 * and a task that cannot be placed fails the migration instead of being given a guessed warehouse.
 */
class FulfillmentTaskContextMigrationTest {

    private static final String BEFORE = "20261013000600";
    private static final String HCM = "md5('demo:wh:HCM')::uuid";

    private static Flyway flyway(PostgreSQLContainer<?> pg, String target) {
        var config = Flyway.configure().dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
                .locations("classpath:db/migration", "classpath:db/demo");
        return (target == null ? config : config.target(target)).load();
    }

    private static void order(Statement sql, String key, String number, String status, String warehouse)
            throws SQLException {
        sql.executeUpdate("""
                INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount,
                                                     currency, placed_at, created_at, warehouse_id)
                VALUES (md5('mig:%s')::uuid, '%s', 'c0000000-0000-4000-8000-000000000001', gen_random_uuid(), '%s',
                        1000, 'VND', NOW(), NOW(), %s)""".formatted(key, number, status, warehouse));
    }

    private static void pick(Statement sql, String key) throws SQLException {
        sql.executeUpdate("""
                INSERT INTO fulfillment.pick (id, order_id, status, created_at)
                VALUES (md5('mig:pick:%1$s')::uuid, md5('mig:%1$s')::uuid, 'PENDING', NOW())""".formatted(key));
        sql.executeUpdate("""
                INSERT INTO fulfillment.pack (id, pick_id, status, created_at)
                VALUES (gen_random_uuid(), md5('mig:pick:%s')::uuid, 'PENDING', NOW())""".formatted(key));
    }

    @Test
    void existingTasksGetTheirOrderNumberAndWarehouse() throws Exception {
        try (var pg = new PostgreSQLContainer<>("postgres:16-alpine")) {
            pg.start();
            flyway(pg, BEFORE).migrate();
            try (Connection connection = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
                    Statement sql = connection.createStatement()) {
                // A released order: its own number and warehouse.
                order(sql, "released", "SO-MIG-RELEASED", "IN_FULFILMENT", HCM);
                pick(sql, "released");
                // A stock-only order picked from CONFIRMED: no warehouse on the order, so the one its hold sits in.
                order(sql, "confirmed", "SO-MIG-CONFIRMED", "IN_FULFILMENT", "NULL");
                sql.executeUpdate("""
                        INSERT INTO inventory.stock_reservation (id, stock_item_id, order_id, root_request_id, request_id,
                                                                 quantity, reserved_at, expires_at, status)
                        SELECT md5('mig:res')::uuid, id, md5('mig:confirmed')::uuid, gen_random_uuid(), gen_random_uuid(),
                               1, NOW(), NOW() + INTERVAL '1 day', 'HELD'
                          FROM inventory.stock_item WHERE location_code LIKE 'HCM-%' AND on_hand > reserved
                         ORDER BY id LIMIT 1""");
                sql.executeUpdate("UPDATE inventory.stock_item SET reserved = reserved + 1 "
                        + "WHERE id = (SELECT stock_item_id FROM inventory.stock_reservation WHERE id = md5('mig:res')::uuid)");
                pick(sql, "confirmed");

                assertThat(flyway(pg, null).migrate().migrationsExecuted).isPositive();

                try (var rows = sql.executeQuery("""
                        SELECT o.order_number, p.order_number, p.warehouse_id = md5('demo:wh:HCM')::uuid
                          FROM fulfillment.pick p JOIN ordering.customer_order o ON o.id = p.order_id
                         WHERE p.id IN (md5('mig:pick:released')::uuid, md5('mig:pick:confirmed')::uuid)
                         ORDER BY o.order_number""")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString(1)).isEqualTo("SO-MIG-CONFIRMED");
                    assertThat(rows.getString(2)).isEqualTo("SO-MIG-CONFIRMED");
                    assertThat(rows.getBoolean(3)).as("warehouse of the hold").isTrue();
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString(2)).isEqualTo("SO-MIG-RELEASED");
                    assertThat(rows.getBoolean(3)).as("warehouse of the order").isTrue();
                    assertThat(rows.next()).isFalse();
                }
                try (var rows = sql.executeQuery("""
                        SELECT column_name, is_nullable FROM information_schema.columns
                         WHERE table_schema = 'fulfillment' AND table_name = 'pick'
                           AND column_name IN ('order_number', 'warehouse_id')""")) {
                    int seen = 0;
                    while (rows.next()) {
                        assertThat(rows.getString(2)).isEqualTo("NO");
                        seen++;
                    }
                    assertThat(seen).isEqualTo(2);
                }
            }
        }
    }

    @Test
    void aTaskThatCannotBePlacedFailsTheMigrationAndNamesIt() throws Exception {
        try (var pg = new PostgreSQLContainer<>("postgres:16-alpine")) {
            pg.start();
            flyway(pg, BEFORE).migrate();
            try (Connection connection = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
                    Statement sql = connection.createStatement()) {
                // Not released and nothing held: there is no honest warehouse for this task.
                order(sql, "orphan", "SO-MIG-ORPHAN", "IN_FULFILMENT", "NULL");
                pick(sql, "orphan");
                String orphanPick;
                try (var row = sql.executeQuery("SELECT md5('mig:pick:orphan')::uuid::text")) {
                    row.next();
                    orphanPick = row.getString(1);
                }
                var latest = flyway(pg, null);

                assertThatThrownBy(latest::migrate).hasMessageContaining("SCRUM-466").hasMessageContaining(orphanPick);
            }
        }
    }
}
