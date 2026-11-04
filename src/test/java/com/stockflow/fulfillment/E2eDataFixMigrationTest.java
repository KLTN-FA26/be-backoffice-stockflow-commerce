package com.stockflow.fulfillment;

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
 * The data fixes of the E2E QA run (2026-10-21) on a database migrated up to before them, holding the shapes the
 * deployed test data has:
 * <ul>
 *   <li>V20261021001100 - picks without their pack row (the seed's PICKING picks) get one: PENDING, or CANCELLED for a
 *       cancelled pick; a pick that has its row is left alone.</li>
 *   <li>V20261021001200 - pending cancellation requests on orders that can no longer be cancelled (packed, shipped,
 *       delivered, cancelled) close as EXPIRED with a note; one on an order still cancellable stays PENDING.</li>
 * </ul>
 */
class E2eDataFixMigrationTest {

    private static final String BEFORE = "20261021000500";
    private static final String HCM = "md5('demo:wh:HCM')::uuid";
    private static final String EDITOR = "md5('demo:user:editor')::uuid";
    private static final String CUSTOMER = "'c0000000-0000-4000-8000-000000000001'";

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

    private static String order(String key, String status, boolean packed) {
        return """
                INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount,
                                                     currency, placed_at, created_at, warehouse_id, packed_at,
                                                     cancellation_reason_code, cancellation_reason, shipped_at,
                                                     delivered_at, hold_reason, status_before_hold)
                VALUES (md5('e2e:%1$s')::uuid, 'SO-E2E-%1$s', %2$s, gen_random_uuid(), '%3$s', 1000, 'VND', NOW(),
                        NOW(), %4$s, %5$s, %6$s, %7$s, %8$s, %9$s, %10$s, %11$s);
                """.formatted(key, CUSTOMER, status, HCM, packed ? "NOW()" : "NULL",
                status.equals("CANCELLED") ? "'OUT_OF_STOCK'" : "NULL",
                status.equals("CANCELLED") ? "'out of stock'" : "NULL",
                status.equals("SHIPPED") || status.equals("DELIVERED") ? "NOW()" : "NULL",
                status.equals("DELIVERED") ? "NOW()" : "NULL",
                status.equals("ON_HOLD") ? "'ADDRESS'" : "NULL",
                status.equals("ON_HOLD") ? "'PACKED'" : "NULL");
    }

    private static String pick(String key, String status) {
        return """
                INSERT INTO fulfillment.pick (id, order_id, order_number, warehouse_id, status, assigned_user_id,
                                              assigned_at, created_at)
                VALUES (md5('e2e:pick:%1$s')::uuid, md5('e2e:%1$s')::uuid, 'SO-E2E-%1$s', %2$s, '%3$s', %4$s, NOW(),
                        NOW());
                """.formatted(key, HCM, status, EDITOR);
    }

    private static String request(String key, String status) {
        return """
                INSERT INTO ordering.order_cancellation_request (id, order_id, reason_code, requested_at, status,
                                                                 decided_at, decision_note)
                VALUES (md5('e2e:req:%1$s')::uuid, md5('e2e:%1$s')::uuid, 'CUSTOMER_REQUEST', NOW(), '%2$s', %3$s,
                        %4$s);
                """.formatted(key, status, status.equals("PENDING") ? "NULL" : "NOW()",
                status.equals("REJECTED") ? "'no'" : "NULL");
    }

    @Test
    void missingPackRowsAndUndecidablePendingRequestsAreFixed() throws Exception {
        try (var pg = new PostgreSQLContainer<>("postgres:16-alpine")) {
            pg.start();
            flyway(pg, BEFORE).migrate();
            try (Connection connection = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(),
                    pg.getPassword()); Statement sql = connection.createStatement()) {
                sql.execute(order("picking", "PICKING", false) + pick("picking", "PICKING"));
                sql.execute(order("gone", "CANCELLED", false) + pick("gone", "CANCELLED"));
                sql.execute(order("haspack", "PICKING", false) + pick("haspack", "PICKED") + """
                        INSERT INTO fulfillment.pack (id, pick_id, status, packed_at, started_at, created_at)
                        VALUES (md5('e2e:pack:haspack')::uuid, md5('e2e:pick:haspack')::uuid, 'PACKING', NULL, NOW(),
                                NOW());
                        """);
                sql.execute(order("delivered", "DELIVERED", true) + request("delivered", "PENDING"));
                sql.execute(order("packedhold", "ON_HOLD", true) + request("packedhold", "PENDING"));
                sql.execute(order("cancelled", "CANCELLED", false) + request("cancelled", "PENDING"));
                sql.execute(order("open", "PICKING", false) + request("open", "PENDING"));
                sql.execute(order("rejected", "SHIPPED", true) + request("rejected", "REJECTED"));

                assertThat(flyway(pg, null).migrate().migrationsExecuted).isPositive();

                assertThat(rows(sql, """
                        SELECT o.order_number, k.status, k.packed_at IS NULL, k.id = md5('e2e:pack:haspack')::uuid
                          FROM fulfillment.pick p JOIN ordering.customer_order o ON o.id = p.order_id
                          LEFT JOIN fulfillment.pack k ON k.pick_id = p.id
                         WHERE o.order_number LIKE 'SO-E2E-%' ORDER BY o.order_number"""))
                        .containsExactly("SO-E2E-gone|CANCELLED|t|f", "SO-E2E-haspack|PACKING|t|t",
                                "SO-E2E-picking|PENDING|t|f");
                assertThat(rows(sql, "SELECT count(*) FROM fulfillment.pick p WHERE NOT EXISTS "
                        + "(SELECT 1 FROM fulfillment.pack k WHERE k.pick_id = p.id)")).containsExactly("0");

                assertThat(rows(sql, """
                        SELECT o.order_number, r.status, r.decided_by IS NULL, r.decided_at IS NOT NULL, r.decision_note
                          FROM ordering.order_cancellation_request r
                          JOIN ordering.customer_order o ON o.id = r.order_id
                         WHERE o.order_number LIKE 'SO-E2E-%' ORDER BY o.order_number""")).containsExactly(
                        "SO-E2E-cancelled|EXPIRED|t|t|Closed: the order was cancelled.",
                        "SO-E2E-delivered|EXPIRED|t|t|Closed: the order is DELIVERED and can no longer be cancelled; "
                                + "ask for a return instead.",
                        "SO-E2E-open|PENDING|t|f|null",
                        "SO-E2E-packedhold|EXPIRED|t|t|Closed: the order is PACKED and can no longer be cancelled; "
                                + "ask for a return instead.",
                        "SO-E2E-rejected|REJECTED|t|t|no");
            }
        }
    }
}
