package com.stockflow.order;

import com.stockflow.support.RawSql;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V20261102040000 (SCRUM-502) on the real upgrade path: a database migrated to the migration before it, holding a
 * quote, and an order with a print line and a stock line written before the requested delivery date existed, then
 * upgraded. Proves the old rows keep everything and read NULL (nothing back-filled), the new date is stored on both
 * tables, and the table refuses a date on a stock line.
 */
class RequestedDeliveryDateMigrationUpgradeTest {

    private static final String BEFORE = "20261101040100";
    private static final String MIGRATION = "20261102040000";

    @Test
    void upgradeAddsTheDateWithoutTouchingOldRowsAndKeepsItToPrintLines() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            flyway(postgres, BEFORE).migrate();
            try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())) {
                UUID customer = UUID.randomUUID();
                RawSql.update(db, "INSERT INTO customer.customer (id, full_name, email, status, version, created_at) "
                        + "VALUES (?, 'Old customer', 'old@example.com', 'ACTIVE', 0, NOW())", customer);
                UUID quote = UUID.randomUUID();
                RawSql.update(db, "INSERT INTO ordering.quote (id, quote_number, request_id, customer_id, status, "
                        + "payment_term, valid_until) VALUES (?, 'QT-OLD-1', gen_random_uuid(), ?, 'DRAFT', 'PREPAID', "
                        + "NOW() + interval '7 days')", quote, customer);
                UUID order = UUID.randomUUID();
                RawSql.update(db, "INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, "
                        + "total_amount, currency, placed_at, version, created_at, payment_term, paid_amount, paid_in_full_at) "
                        + "VALUES (?, 'SO-OLD-1', ?, gen_random_uuid(), 'CONFIRMED', 7000, 'VND', NOW(), 0, NOW(), "
                        + "'PREPAID', 7000, NOW())", order, customer);
                UUID stockLine = UUID.randomUUID();
                UUID printLine = UUID.randomUUID();
                // The variant and the design snapshot are other modules' rows; their foreign keys are not what this
                // test is about.
                RawSql.update(db, "SET session_replication_role = replica");
                RawSql.update(db, "INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency) "
                        + "VALUES (?, ?, 'LID-90', 2, 500, 'VND')", stockLine, order);
                RawSql.update(db, "INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency, "
                        + "design_snapshot_id, design_checksum) VALUES (?, ?, 'CUP-12OZ', 3, 2000, 'VND', ?, ?)",
                        printLine, order, UUID.randomUUID(), "a".repeat(64));
                RawSql.update(db, "SET session_replication_role = DEFAULT");

                var result = flyway(postgres, MIGRATION).migrate();
                assertThat(result.migrations).extracting(m -> m.version).contains(MIGRATION);

                // Old rows: nothing lost, no date invented.
                assertThat(RawSql.number(db, "SELECT count(*) FROM ordering.quote WHERE id = ? "
                        + "AND requested_delivery_date IS NULL AND quote_number = 'QT-OLD-1'", quote)).isEqualTo(1);
                assertThat(RawSql.number(db, "SELECT count(*) FROM ordering.order_line WHERE order_id = ? "
                        + "AND requested_delivery_date IS NULL", order)).isEqualTo(2);
                assertThat(RawSql.text(db, "SELECT sku || ' ' || quantity FROM ordering.order_line WHERE id = ?", printLine))
                        .isEqualTo("CUP-12OZ 3");

                // The date is kept on a quote, and on a print line; a stock line has none to keep.
                RawSql.update(db, "UPDATE ordering.quote SET requested_delivery_date = DATE '2026-12-15' WHERE id = ?", quote);
                assertThat(RawSql.text(db, "SELECT requested_delivery_date::text FROM ordering.quote WHERE id = ?", quote))
                        .isEqualTo("2026-12-15");
                RawSql.update(db, "UPDATE ordering.order_line SET requested_delivery_date = DATE '2026-12-15' WHERE id = ?",
                        printLine);
                assertThat(RawSql.text(db, "SELECT requested_delivery_date::text FROM ordering.order_line WHERE id = ?",
                        printLine)).isEqualTo("2026-12-15");
                assertThatThrownBy(() -> RawSql.update(db, "UPDATE ordering.order_line SET requested_delivery_date = "
                        + "DATE '2026-12-15' WHERE id = ?", stockLine))
                        .hasMessageContaining("ck_order_line_requested_delivery");
            }
        }
    }

    private static Flyway flyway(PostgreSQLContainer<?> postgres, String target) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }
}
