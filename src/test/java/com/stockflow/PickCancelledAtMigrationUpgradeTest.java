package com.stockflow;

import com.stockflow.support.RawSql;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V20261204005100 on the real upgrade path (WF04 G8, kltn-docs 07 §4.5 step 13). It adds {@code fulfillment.pick.cancelled_at}
 * and backfills it on the tasks already cancelled, from the last write of the row (its cancellation saved it), or from its
 * creation when it was never written again. A task that is not cancelled keeps it null.
 *
 * <p>The rows are written with triggers off ({@code session_replication_role = replica}): what is tested is the migration
 * on rows the old schema accepted. How the queue reads the column is {@code PutBackQueueIntegrationTest}'s.</p>
 */
class PickCancelledAtMigrationUpgradeTest {

    private static final String BEFORE = "20261203000300";
    private static final String MIGRATION = "20261204005100";
    private static final String CUSTOMER = "00000000-0000-4000-8000-00000000e0a0";
    private static final String WAREHOUSE = "00000000-0000-4000-8000-00000000e0c1";

    @Test
    void cancelledTasksTakeTheirLastWriteAndTheOthersStayNull() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            flyway(postgres, BEFORE).migrate();
            try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())) {
                seed(db);

                var result = flyway(postgres, MIGRATION).migrate();
                assertThat(result.migrations).extracting(m -> m.version).containsExactly(MIGRATION);

                assertThat(RawSql.texts(db, "SELECT order_number || ':' || status || ':' || "
                        + "coalesce(cancelled_at::text, '-') FROM fulfillment.pick WHERE order_number LIKE 'SO-UPG-P%' "
                        + "ORDER BY order_number"))
                        .as("a cancelled task takes its last write, else its creation; the others are not touched")
                        .containsExactly("SO-UPG-P1:CANCELLED:2026-11-10 08:00:00+00",
                                "SO-UPG-P2:CANCELLED:2026-11-09 07:00:00+00", "SO-UPG-P3:PICKING:-",
                                "SO-UPG-P4:PENDING:-");
                assertThat(RawSql.number(db, "SELECT count(*) FROM fulfillment.pick WHERE status = 'CANCELLED' "
                        + "AND cancelled_at IS NULL")).isZero();
            }
        }
    }

    private static void seed(Connection db) throws SQLException {
        RawSql.update(db, "SET session_replication_role = replica");
        String order = "INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, "
                + "total_amount, currency, placed_at, created_at, contract_signed_at) VALUES (gen_random_uuid(), ?, "
                + "?::uuid, gen_random_uuid(), 'CONFIRMED', 1000, 'VND', NOW(), NOW(), NOW())";
        String pick = "INSERT INTO fulfillment.pick (id, order_id, order_number, warehouse_id, status, created_at, "
                + "last_modified_at, needs_put_back) VALUES (gen_random_uuid(), (SELECT id FROM ordering.customer_order "
                + "WHERE order_number = ?), ?, ?::uuid, ?, ?::timestamptz, ?::timestamptz, ?)";
        // Cancelled and written again afterwards: the last write is when the cancellation saved it.
        RawSql.update(db, order, "SO-UPG-P1", CUSTOMER);
        RawSql.update(db, pick, "SO-UPG-P1", "SO-UPG-P1", WAREHOUSE, "CANCELLED", "2026-11-08 08:00:00+00",
                "2026-11-10 08:00:00+00", true);
        // Cancelled and never written again after creation.
        RawSql.update(db, order, "SO-UPG-P2", CUSTOMER);
        RawSql.update(db, pick, "SO-UPG-P2", "SO-UPG-P2", WAREHOUSE, "CANCELLED", "2026-11-09 07:00:00+00", null,
                false);
        RawSql.update(db, order, "SO-UPG-P3", CUSTOMER);
        RawSql.update(db, pick, "SO-UPG-P3", "SO-UPG-P3", WAREHOUSE, "PICKING", "2026-11-09 07:00:00+00",
                "2026-11-09 09:00:00+00", false);
        RawSql.update(db, order, "SO-UPG-P4", CUSTOMER);
        RawSql.update(db, pick, "SO-UPG-P4", "SO-UPG-P4", WAREHOUSE, "PENDING", "2026-11-09 07:00:00+00", null,
                false);
        RawSql.update(db, "SET session_replication_role = origin");
    }

    private static Flyway flyway(PostgreSQLContainer<?> postgres, String target) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }
}
