package com.stockflow;

import com.stockflow.support.RawSql;
import java.sql.Connection;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V20261108001500 on the real upgrade path (kltn-docs 09 §4.4, BR-25; team plan 3-3): a waybill file kept with a load
 * before the import existed is kept as it was, one stored file may now be several loads' (an import's), never twice one
 * load's; and importing reaches the dispatcher, the warehouse manager and the administrator only, moving their versions
 * (ADR-0008).
 */
class WaybillImportMigrationUpgradeTest {

    private static final String BEFORE = "20261108001400";
    private static final String MIGRATION = "20261108001500";

    @Test
    void upgradeKeepsWaybillFilesAndGrantsTheImport() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            flyway(postgres, BEFORE).migrate();
            try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())) {
                String user = "00000000-0000-4000-8000-0000000000aa";
                String warehouse = "00000000-0000-4000-8000-0000000000e1";
                RawSql.update(db, "INSERT INTO identity.app_user (id, username, email, password_hash, status, version, "
                        + "created_at) VALUES (?::uuid, 'qa', 'qa@example.com', '!x', 'ACTIVE', 0, NOW())", user);
                RawSql.update(db, "INSERT INTO warehouse.warehouse (id, name, status, prefix, address, map_unit, "
                        + "map_width, map_height, version, created_at) VALUES (?::uuid, 'QA', 'ACTIVE', 'QAW', '1 QA', "
                        + "'M', 10, 10, 0, NOW())", warehouse);
                RawSql.update(db, "INSERT INTO fulfillment.carrier (id, code, name) VALUES "
                        + "('00000000-0000-4000-8000-0000000000c1', 'TRUCK-A', 'Xe A')");
                for (String code : new String[] {"LD-1", "LD-2"}) {
                    RawSql.update(db, "INSERT INTO fulfillment.dispatch_load (id, code, request_id, warehouse_id, "
                            + "carrier_id, status, cancelled_at, cancel_reason, created_at) VALUES (gen_random_uuid(), "
                            + "?, gen_random_uuid(), ?::uuid, '00000000-0000-4000-8000-0000000000c1', 'CANCELLED', "
                            + "NOW(), 'x', NOW())", code, warehouse);
                }
                RawSql.update(db, "INSERT INTO fulfillment.load_waybill_file (id, load_id, storage_key, file_name, "
                        + "content_type, size_bytes, uploaded_by, uploaded_at) SELECT gen_random_uuid(), id, "
                        + "'documents/2026/11/08/old.pdf', 'old.pdf', 'application/pdf', 10, ?::uuid, NOW() "
                        + "FROM fulfillment.dispatch_load WHERE code = 'LD-1'", user);
                long staff = version(db, "WAREHOUSE_STAFF");
                long manager = version(db, "WAREHOUSE_MANAGER");
                long admin = version(db, "SYSTEM_ADMIN");
                long sales = version(db, "SALES_STAFF");

                var result = flyway(postgres, MIGRATION).migrate();
                assertThat(result.migrations).extracting(m -> m.version).containsExactly(MIGRATION);

                assertThat(RawSql.number(db, "SELECT count(*) FROM fulfillment.load_waybill_file WHERE "
                        + "storage_key = 'documents/2026/11/08/old.pdf' AND waybill_import_id IS NULL")).isEqualTo(1);
                // One stored file, several loads: an import's file kept with each load a line applied to.
                RawSql.update(db, "INSERT INTO fulfillment.waybill_import (id, file_name, storage_key, uploaded_by, "
                        + "uploaded_at, rows_total, rows_applied, rows_rejected) VALUES "
                        + "('00000000-0000-4000-8000-0000000000f1', 'van-don.csv', 'waybill-imports/2026/11/09/a.csv', "
                        + "?::uuid, NOW(), 2, 2, 0)", user);
                assertThat(RawSql.update(db, "INSERT INTO fulfillment.load_waybill_file (id, load_id, storage_key, "
                        + "file_name, content_type, size_bytes, uploaded_by, uploaded_at, waybill_import_id) "
                        + "SELECT gen_random_uuid(), id, 'waybill-imports/2026/11/09/a.csv', 'van-don.csv', 'text/csv', 10, "
                        + "?::uuid, NOW(), '00000000-0000-4000-8000-0000000000f1' FROM fulfillment.dispatch_load", user))
                        .isEqualTo(2);
                RawSql.refused(db, "uk_load_waybill_file_load_key", "INSERT INTO fulfillment.load_waybill_file (id, "
                        + "load_id, storage_key, file_name, content_type, size_bytes, uploaded_by, uploaded_at) "
                        + "SELECT gen_random_uuid(), id, 'documents/2026/11/08/old.pdf', 'old.pdf', 'application/pdf', "
                        + "10, ?::uuid, NOW() FROM fulfillment.dispatch_load WHERE code = 'LD-1'", user);
                RawSql.refused(db, "ck_waybill_import_rows", "INSERT INTO fulfillment.waybill_import (id, file_name, "
                        + "storage_key, uploaded_by, uploaded_at, rows_total, rows_applied, rows_rejected) VALUES "
                        + "(gen_random_uuid(), 'b.csv', 'waybill-imports/2026/11/09/b.csv', ?::uuid, NOW(), 1001, 1001, 0)",
                        user);
                RawSql.refused(db, "trg_waybill_import_append_only", "DELETE FROM fulfillment.waybill_import");
                // An import's file is kept with a load only as the import's.
                RawSql.refused(db, "ck_load_waybill_file_key", "INSERT INTO fulfillment.load_waybill_file (id, "
                        + "load_id, storage_key, file_name, content_type, size_bytes, uploaded_by, uploaded_at) "
                        + "SELECT gen_random_uuid(), id, 'waybill-imports/2026/11/09/c.csv', 'c.csv', 'text/csv', 10, "
                        + "?::uuid, NOW() FROM fulfillment.dispatch_load WHERE code = 'LD-1'", user);

                assertThat(RawSql.texts(db, "SELECT r.code || ':' || p.action FROM identity.role_permission rp "
                        + "JOIN identity.app_role r ON r.id = rp.role_id JOIN identity.permission p "
                        + "ON p.id = rp.permission_id WHERE p.resource = 'fulfillment-waybill-imports' ORDER BY 1"))
                        .containsExactly("SYSTEM_ADMIN:CREATE", "SYSTEM_ADMIN:READ", "WAREHOUSE_MANAGER:CREATE",
                                "WAREHOUSE_MANAGER:READ", "WAREHOUSE_STAFF:CREATE", "WAREHOUSE_STAFF:READ");
                assertThat(version(db, "WAREHOUSE_STAFF")).isEqualTo(staff + 1);
                assertThat(version(db, "WAREHOUSE_MANAGER")).isEqualTo(manager + 1);
                assertThat(version(db, "SYSTEM_ADMIN")).isEqualTo(admin + 1);
                assertThat(version(db, "SALES_STAFF")).isEqualTo(sales);
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
