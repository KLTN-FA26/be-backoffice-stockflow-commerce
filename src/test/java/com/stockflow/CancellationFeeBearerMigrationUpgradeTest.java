package com.stockflow;

import com.stockflow.support.RawSql;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V20270128000550 on the real upgrade path (D46, D50): every fee kept before it was the customer's, so it is backfilled
 * as CUSTOMER - an agreed fee and a share on a cancelled order, and the fee of a closure of the rest on its own row -
 * while an order, a closure and a settlement without a fee keep no bearer and the company bears nothing.
 */
class CancellationFeeBearerMigrationUpgradeTest {

    private static final String BEFORE = "20270128000540";
    private static final String MIGRATION = "20270128000550";
    private static final String CUSTOMER = "c0000000-0000-4000-8000-000000000001";

    @Test
    void feesKeptBeforeAreTheCustomersAndNoFeeHasNoBearer() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            flyway(postgres, BEFORE).migrate();
            try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())) {
                // Rows as the application left them: written with triggers and foreign keys off, only the shape matters.
                RawSql.update(db, "SET session_replication_role = replica");
                for (String order : new String[] {"agreed", "share", "plain"}) {
                    RawSql.update(db, "INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, "
                            + "status, total_amount, currency, placed_at, created_at, printing_started_at, "
                            + "cancellation_reason_code, cancellation_reason, cancellation_retained_amount"
                            + (order.equals("agreed") ? ", cancellation_fee_amount, cancellation_fee_reason, "
                            + "cancellation_fee_detail" : "") + ") VALUES (md5('up:" + order + "')::uuid, 'SO-UP-"
                            + order.toUpperCase() + "', '" + CUSTOMER + "', gen_random_uuid(), 'CANCELLED', 2000000, "
                            + "'VND', NOW(), NOW(), NOW(), 'OUT_OF_STOCK', 'OUT_OF_STOCK', 0"
                            + (order.equals("agreed") ? ", 500000, 'Cups printed', 'Printed 1,000'" : "") + ")");
                }
                // A share is settled by payment: the order keeps only the settlement's word for it.
                settlement(db, "share", "ORDER_CANCELLED", "md5('up:share')::uuid", 100_000, "10", null);
                settlement(db, "plain", "ORDER_CANCELLED", "md5('up:plain')::uuid", 0, null, null);
                // A closure of the rest that kept a fee, and one that did not.
                for (String closure : new String[] {"fee", "free"}) {
                    RawSql.update(db, "INSERT INTO ordering.order_remainder_closure (id, order_id, closure_id, "
                            + "reason_code, closed_at) VALUES (md5('up:row:" + closure + "')::uuid, "
                            + "md5('up:agreed')::uuid, md5('up:closure:" + closure + "')::uuid, 'OUT_OF_STOCK', NOW())");
                }
                settlement(db, "agreed", "REMAINDER_CLOSED", "md5('up:closure:fee')::uuid", 200_000, null, "Printed");
                RawSql.update(db, "SET session_replication_role = origin");

                var result = flyway(postgres, MIGRATION).migrate();
                assertThat(result.migrations).extracting(m -> m.version).containsExactly(MIGRATION);

                assertThat(RawSql.texts(db, "SELECT order_number || ':' || coalesce(cancellation_fee_bearer, '-') "
                        + "FROM ordering.customer_order WHERE order_number LIKE 'SO-UP-%' ORDER BY 1"))
                        .as("an agreed fee and a share are the customer's; no fee has no bearer")
                        .containsExactly("SO-UP-AGREED:CUSTOMER", "SO-UP-PLAIN:-", "SO-UP-SHARE:CUSTOMER");
                assertThat(RawSql.texts(db, "SELECT (closure_id = md5('up:closure:fee')::uuid)::text || ':' || "
                        + "coalesce(fee_amount::text, '-') || ':' || coalesce(fee_bearer, '-') FROM "
                        + "ordering.order_remainder_closure WHERE order_id = md5('up:agreed')::uuid ORDER BY 1"))
                        .as("the closure that kept a fee says who bears it; the other keeps none")
                        .containsExactly("false:-:-", "true:200000.0000:CUSTOMER");
                assertThat(RawSql.texts(db, "SELECT kind || ':' || coalesce(fee_bearer, '-') || ':' || "
                        + "company_fee_amount::bigint FROM payment.order_settlement WHERE order_number LIKE 'SO-UP-%' "
                        + "ORDER BY order_number, kind"))
                        .as("each settlement that kept a fee is the customer's, the company bears nothing")
                        .containsExactly("REMAINDER_CLOSED:CUSTOMER:0", "ORDER_CANCELLED:-:0",
                                "ORDER_CANCELLED:CUSTOMER:0");
                RawSql.refused(db, "ck_order_settlement_fee_bearer",
                        "UPDATE payment.order_settlement SET company_fee_amount = 1 WHERE order_number = 'SO-UP-PLAIN'");
            }
        }
    }

    private static void settlement(Connection db, String order, String kind, String source, long fee, String percent,
                                   String reason) throws Exception {
        RawSql.update(db, "INSERT INTO payment.order_settlement (id, order_id, contract_id, customer_id, order_number, "
                + "contract_number, kind, source_id, currency, collected_amount, closed_amount, reduced_amount, "
                + "surplus_amount, fee_amount, fee_percent, fee_reason, fee_detail, fee_covered_amount, "
                + "refundable_amount, fee_owed_amount, settled_at) VALUES (gen_random_uuid(), md5('up:" + order
                + "')::uuid, md5('up:contract')::uuid, '" + CUSTOMER + "', 'SO-UP-" + order.toUpperCase()
                + "', 'HD-UP-1', '" + kind + "', " + (kind.equals("ORDER_CANCELLED") ? "md5('up:" + order + "')::uuid"
                : source) + ", 'VND', 1000000, " + (kind.equals("REMAINDER_CLOSED") ? "1000000" : "0") + ", 0, "
                + "1000000, " + fee + ", " + (percent == null ? "NULL" : percent) + ", "
                + (reason == null ? "NULL" : "'" + reason + "'") + ", " + (reason == null ? "NULL" : "'detail'")
                + ", " + fee + ", " + (1_000_000 - fee) + ", 0, NOW())");
    }

    private static Flyway flyway(PostgreSQLContainer<?> postgres, String target) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration", "classpath:db/demo")
                .target(target)
                .load();
    }
}
