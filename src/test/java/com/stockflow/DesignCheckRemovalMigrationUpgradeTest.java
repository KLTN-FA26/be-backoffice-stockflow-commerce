package com.stockflow;

import com.stockflow.support.RawSql;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V20261108000500 on the real upgrade path: a database migrated to the migration before it, holding what the design
 * check at packing left behind - an order held for a design mismatch while it was being picked (its open hold row, a
 * resolved one of the same kind from earlier), its package held with the request id of the completion, the verification
 * record, and the re-verification permission - then upgraded.
 *
 * <p>Proves nothing is lost (every row dropped and every value changed is archived), the order is back where it was
 * held from with the change in its history, the package is back in progress, the resolved hold stays as history, the
 * table and the column are gone, the CHECKs no longer know the hold, and every role that held the permission moves its
 * version.</p>
 */
class DesignCheckRemovalMigrationUpgradeTest {

    private static final String BEFORE = "20261108000400";
    private static final String MIGRATION = "20261108000500";

    @Test
    void upgradeReleasesDesignHoldsKeepingEverythingInTheArchive() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            flyway(postgres, BEFORE).migrate();
            try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())) {
                UUID order = UUID.randomUUID();
                UUID openHold = UUID.randomUUID();
                UUID resolvedHold = UUID.randomUUID();
                UUID pack = UUID.randomUUID();
                UUID verification = UUID.randomUUID();
                long managerVersion = version(db, "WAREHOUSE_MANAGER");
                long adminVersion = version(db, "SYSTEM_ADMIN");
                long staffVersion = version(db, "WAREHOUSE_STAFF");

                // The customer, the warehouse, the pick, the line and the people are not what this test is about:
                // foreign keys and row triggers are off while the old state is written.
                RawSql.update(db, "SET session_replication_role = replica");
                RawSql.update(db, "INSERT INTO ordering.customer_order (id, order_number, request_id, customer_id, status, "
                        + "total_amount, currency, placed_at, created_at, status_before_hold, hold_reason, warehouse_id, "
                        + "released_at, released_by) VALUES (?, 'SO-OLD-9', gen_random_uuid(), gen_random_uuid(), "
                        + "'ON_HOLD', 100, 'VND', NOW(), NOW(), 'PICKING', 'DESIGN_MISMATCH', gen_random_uuid(), NOW(), "
                        + "gen_random_uuid())", order);
                RawSql.update(db, "INSERT INTO ordering.order_hold (id, order_id, reason, raised_at, created_at) VALUES "
                        + "(?, ?, 'DESIGN_MISMATCH', NOW(), NOW())", openHold, order);
                RawSql.update(db, "INSERT INTO ordering.order_hold (id, order_id, reason, raised_at, created_at, "
                        + "resolved_at, resolved_by, resolution_note) VALUES (?, ?, 'DESIGN_MISMATCH', NOW() - INTERVAL "
                        + "'1 day', NOW(), NOW() - INTERVAL '20 hours', gen_random_uuid(), 'reprinted')", resolvedHold,
                        order);
                RawSql.update(db, "INSERT INTO fulfillment.pack (id, pick_id, status, created_at, started_at, "
                        + "held_request_id) VALUES (?, gen_random_uuid(), 'ON_HOLD', NOW(), NOW(), gen_random_uuid())",
                        pack);
                RawSql.update(db, "INSERT INTO fulfillment.design_verification (id, pack_id, order_id, order_line_id, "
                        + "snapshot_id, expected_checksum, actual_checksum, result, verified_by, verified_at, created_at) "
                        + "VALUES (?, ?, ?, gen_random_uuid(), gen_random_uuid(), 'sha-confirmed', 'sha-other', "
                        + "'MISMATCH', gen_random_uuid(), NOW(), NOW())", verification, pack, order);
                RawSql.update(db, "SET session_replication_role = DEFAULT");

                var result = flyway(postgres, MIGRATION).migrate();
                assertThat(result.migrations).extracting(m -> m.version).containsExactly(MIGRATION);

                // The order is back where it was held from, the change in its history; the package is back in progress.
                assertThat(RawSql.text(db, "SELECT status || ':' || coalesce(hold_reason, '-') || ':' || "
                        + "coalesce(status_before_hold, '-') FROM ordering.customer_order WHERE id = ?", order))
                        .isEqualTo("PICKING:-:-");
                assertThat(RawSql.texts(db, "SELECT from_status || '>' || to_status FROM ordering.order_status_history "
                        + "WHERE order_id = ?", order)).containsExactly("ON_HOLD>PICKING");
                assertThat(RawSql.text(db, "SELECT status FROM fulfillment.pack WHERE id = ?", pack))
                        .isEqualTo("PACKING");
                // The open hold row goes (nobody resolved it); the resolved one stays as history.
                assertThat(RawSql.texts(db, "SELECT id::text FROM ordering.order_hold WHERE order_id = ?", order))
                        .containsExactly(resolvedHold.toString());

                // Archived before it went or changed.
                assertThat(RawSql.text(db, "SELECT row_data ->> 'actual_checksum' FROM platform.legacy_archive WHERE "
                        + "source_table = 'fulfillment.design_verification' AND row_id = ?", verification))
                        .isEqualTo("sha-other");
                assertThat(RawSql.text(db, "SELECT row_data ->> 'status' FROM platform.legacy_archive WHERE "
                        + "source_table = 'fulfillment.pack.design_hold' AND row_id = ?", pack)).isEqualTo("ON_HOLD");
                assertThat(RawSql.text(db, "SELECT (row_data ->> 'hold_reason') || ':' || (row_data ->> "
                        + "'status_before_hold') FROM platform.legacy_archive WHERE source_table = "
                        + "'ordering.customer_order.design_hold' AND row_id = ?", order))
                        .isEqualTo("DESIGN_MISMATCH:PICKING");
                assertThat(RawSql.number(db, "SELECT count(*) FROM platform.legacy_archive WHERE source_table = "
                        + "'ordering.order_hold' AND row_id = ?", openHold)).isEqualTo(1);

                // What the design check kept in the schema is gone; the CHECKs no longer know its hold.
                assertThat(RawSql.number(db, "SELECT count(*) FROM information_schema.tables WHERE table_schema = "
                        + "'fulfillment' AND table_name = 'design_verification'")).isZero();
                assertThat(RawSql.number(db, "SELECT count(*) FROM information_schema.columns WHERE table_schema = "
                        + "'fulfillment' AND table_name = 'pack' AND column_name = 'held_request_id'")).isZero();
                RawSql.refused(db, "ck_pack_status", "UPDATE fulfillment.pack SET status = 'ON_HOLD' WHERE id = ?",
                        pack);
                RawSql.refused(db, "ck_order_hold_reason", "UPDATE ordering.customer_order SET status = 'ON_HOLD', "
                        + "hold_reason = 'DESIGN_MISMATCH', status_before_hold = 'PICKING' WHERE id = ?", order);

                // Every role that held the re-verification permission moves its version; one that held none does not.
                assertThat(RawSql.number(db, "SELECT count(*) FROM identity.permission WHERE code LIKE "
                        + "'fulfillment-design-reverification:%'")).isZero();
                assertThat(version(db, "WAREHOUSE_MANAGER")).isEqualTo(managerVersion + 1);
                assertThat(version(db, "SYSTEM_ADMIN")).isEqualTo(adminVersion + 1);
                assertThat(version(db, "WAREHOUSE_STAFF")).isEqualTo(staffVersion);
            }
        }
    }

    private static long version(Connection db, String role) throws Exception {
        return RawSql.number(db, "SELECT version FROM identity.app_role WHERE code = ?", role);
    }

    private static Flyway flyway(PostgreSQLContainer<?> postgres, String target) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }
}
