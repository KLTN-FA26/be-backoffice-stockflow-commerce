package com.stockflow;

import com.stockflow.support.RawSql;
import java.sql.Connection;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V20261108001400 on the real upgrade path (kltn-docs 09 §4.4, BR-23, BR-24; team plan 3-2): a load that left before
 * the rule carries no waybill number and stays as it is - it can still be given one - while every load leaving from now
 * on must carry one; a number is unique within its carrier among live loads; its history is append-only; and the
 * correction permission reaches the warehouse manager and the administrator only, moving their versions (ADR-0008).
 */
class WaybillMigrationUpgradeTest {

    private static final String BEFORE = "20261108001300";
    private static final String MIGRATION = "20261108001400";

    @Test
    void upgradeKeepsOldLoadsAndHoldsNewOnesToTheirWaybill() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            flyway(postgres, BEFORE).migrate();
            try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())) {
                RawSql.update(db, "INSERT INTO identity.app_user (id, username, email, password_hash, status, version, "
                        + "created_at) VALUES ('00000000-0000-4000-8000-0000000000aa', 'qa-dispatcher', "
                        + "'qa-dispatcher@example.com', '!x', 'ACTIVE', 0, NOW())");
                RawSql.update(db, "INSERT INTO fulfillment.carrier (id, code, name) VALUES "
                        + "('00000000-0000-4000-8000-0000000000c1', 'TRUCK-A', 'Xe A'), "
                        + "('00000000-0000-4000-8000-0000000000c2', 'TRUCK-B', 'Xe B')");
                String warehouse = "00000000-0000-4000-8000-0000000000e1";
                RawSql.update(db, "INSERT INTO warehouse.warehouse (id, name, status, prefix, address, map_unit, "
                        + "map_width, map_height, version, created_at) VALUES (?::uuid, 'QA', 'ACTIVE', 'QAW', '1 QA', "
                        + "'M', 10, 10, 0, NOW())", warehouse);
                // A load that left before the rule: a parcel-era load, with no plan vehicle (as V20261108000400 left
                // them) and no waybill number.
                RawSql.update(db, dispatched("00000000-0000-4000-8000-0000000000d1", "LD-OLD-1",
                        "00000000-0000-4000-8000-0000000000c1", null), warehouse);
                long manager = version(db, "WAREHOUSE_MANAGER");
                long admin = version(db, "SYSTEM_ADMIN");
                long staff = version(db, "WAREHOUSE_STAFF");

                var result = flyway(postgres, MIGRATION).migrate();
                assertThat(result.migrations).extracting(m -> m.version).containsExactly(MIGRATION);

                // The old load stands; it can still be given a number.
                assertThat(RawSql.number(db, "SELECT count(*) FROM fulfillment.dispatch_load WHERE id = "
                        + "'00000000-0000-4000-8000-0000000000d1' AND waybill_no IS NULL")).isEqualTo(1);
                assertThat(RawSql.update(db, "UPDATE fulfillment.dispatch_load SET waybill_no = 'OLD-1' WHERE id = "
                        + "'00000000-0000-4000-8000-0000000000d1'")).isEqualTo(1);
                // A load leaving from now on carries one; a number goes with its carrier.
                RawSql.refused(db, "ck_dispatch_load_dispatched_waybill", dispatched(null, "LD-NEW-1",
                        "00000000-0000-4000-8000-0000000000c1", null), warehouse);
                RawSql.refused(db, "ck_dispatch_load_waybill_carrier", "UPDATE fulfillment.dispatch_load SET "
                        + "carrier_id = NULL WHERE id = '00000000-0000-4000-8000-0000000000d1'");
                RawSql.refused(db, "ck_dispatch_load_waybill_no", "UPDATE fulfillment.dispatch_load SET "
                        + "waybill_no = 'OLD 1' WHERE id = '00000000-0000-4000-8000-0000000000d1'");

                // Unique within a carrier among live loads; another carrier, or a cancelled load, may share it.
                RawSql.update(db, "INSERT INTO fulfillment.dispatch_load (id, code, request_id, warehouse_id, "
                        + "carrier_id, status, cancelled_at, cancel_reason, created_at, waybill_no) VALUES "
                        + "(gen_random_uuid(), 'LD-CXL-1', gen_random_uuid(), ?::uuid, "
                        + "'00000000-0000-4000-8000-0000000000c1', 'CANCELLED', NOW(), 'x', NOW(), 'OLD-1')", warehouse);
                RawSql.update(db, dispatched(null, "LD-B-1", "00000000-0000-4000-8000-0000000000c2", "OLD-1"),
                        warehouse);
                RawSql.refused(db, "uk_dispatch_load_waybill", "UPDATE fulfillment.dispatch_load SET waybill_no = "
                        + "'OLD-1', carrier_id = '00000000-0000-4000-8000-0000000000c1' WHERE code = 'LD-B-1'");

                // The history: who and why, append-only.
                RawSql.update(db, "INSERT INTO fulfillment.waybill_change (id, load_id, old_waybill_no, "
                        + "new_waybill_no, source, changed_by, changed_at) VALUES (gen_random_uuid(), "
                        + "'00000000-0000-4000-8000-0000000000d1', NULL, 'OLD-1', 'MANUAL', "
                        + "'00000000-0000-4000-8000-0000000000aa', NOW())");
                RawSql.refused(db, "trg_waybill_change_append_only", "UPDATE fulfillment.waybill_change SET "
                        + "reason = 'rewritten'");
                RawSql.refused(db, "trg_waybill_change_append_only", "DELETE FROM fulfillment.waybill_change");
                RawSql.refused(db, "ck_waybill_change_who", "INSERT INTO fulfillment.waybill_change (id, load_id, "
                        + "new_waybill_no, source, changed_at) VALUES (gen_random_uuid(), "
                        + "'00000000-0000-4000-8000-0000000000d1', 'OLD-2', 'MANUAL', NOW())");
                RawSql.refused(db, "ck_waybill_change_changes", "INSERT INTO fulfillment.waybill_change (id, load_id, "
                        + "old_waybill_no, new_waybill_no, source, changed_at) VALUES (gen_random_uuid(), "
                        + "'00000000-0000-4000-8000-0000000000d1', 'OLD-1', 'OLD-1', 'WEBHOOK', NOW())");

                // Correcting after the vehicle left: the warehouse manager and the administrator, versions moved.
                assertThat(RawSql.texts(db, "SELECT r.code FROM identity.role_permission rp JOIN identity.app_role r "
                        + "ON r.id = rp.role_id JOIN identity.permission p ON p.id = rp.permission_id "
                        + "WHERE p.code = 'fulfillment-waybill-corrections:UPDATE' ORDER BY r.code"))
                        .containsExactly("SYSTEM_ADMIN", "WAREHOUSE_MANAGER");
                assertThat(version(db, "WAREHOUSE_MANAGER")).isEqualTo(manager + 1);
                assertThat(version(db, "SYSTEM_ADMIN")).isEqualTo(admin + 1);
                assertThat(version(db, "WAREHOUSE_STAFF")).isEqualTo(staff);
            }
        }
    }

    /**
     * A DISPATCHED load with no plan vehicle (a parcel-era one), with what its signature records; without a waybill
     * number the column is not named, so the statement also runs before the migration adds it.
     */
    private static String dispatched(String id, String code, String carrier, String waybill) {
        return "INSERT INTO fulfillment.dispatch_load (id, code, request_id, warehouse_id, carrier_id, vehicle_plate, "
                + "status, loading_started_at, dispatched_at, dispatched_by, carrier_representative, shipment_count, "
                + "package_count, created_at" + (waybill == null ? "" : ", waybill_no") + ") VALUES ("
                + (id == null ? "gen_random_uuid()" : "'" + id + "'") + ", '" + code + "', gen_random_uuid(), ?::uuid, '"
                + carrier + "', '51C-1', 'DISPATCHED', NOW(), NOW(), '00000000-0000-4000-8000-0000000000aa', 'Tai', 1, "
                + "1, NOW()" + (waybill == null ? "" : ", '" + waybill + "'") + ")";
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
