package com.stockflow;

import com.stockflow.support.RawSql;
import java.sql.Connection;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V20261108001800 on the real upgrade path (kltn-docs 09 §4.3 steps 2 and 5, E16, E17; 17 §5.1; team plan 3-6): a
 * correction is DELIVERED ↔ DELIVERY_FAILED with a reason (a failure with its reason, OTHER with a note), never
 * rewritten; a proof of delivery is a document kept once; the order may follow a delivery corrected to a failure; the
 * warehouse manager and the administrator correct, their versions moving (ADR-0008).
 */
class DeliveryTrackingMigrationUpgradeTest {

    private static final String BEFORE = "20261108001700";
    private static final String MIGRATION = "20261108001800";
    private static final String USER = "00000000-0000-4000-8000-0000000000aa";
    private static final String WAREHOUSE = "00000000-0000-4000-8000-0000000000e1";
    private static final String SHIPMENT = "00000000-0000-4000-8000-0000000000b1";

    @Test
    void upgradeAddsCorrectionsAndProofsOfDelivery() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            flyway(postgres, BEFORE).migrate();
            try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())) {
                RawSql.update(db, "INSERT INTO identity.app_user (id, username, email, password_hash, status, version, "
                        + "created_at) VALUES (?::uuid, 'qa', 'qa@example.com', '!x', 'ACTIVE', 0, NOW())", USER);
                RawSql.update(db, "INSERT INTO warehouse.warehouse (id, name, status, prefix, address, map_unit, "
                        + "map_width, map_height, version, created_at) VALUES (?::uuid, 'QA', 'ACTIVE', 'QAW', '1 QA', "
                        + "'M', 10, 10, 0, NOW())", WAREHOUSE);
                // A shipment row, enough for the foreign keys; its order is not this test's (the keys off).
                RawSql.update(db, "SET session_replication_role = replica");
                RawSql.update(db, "INSERT INTO fulfillment.shipment (id, code, order_id, warehouse_id, package_count, "
                        + "status, ready_at, version, created_at) VALUES (?::uuid, 'SO-QA-S1', gen_random_uuid(), "
                        + "?::uuid, 1, 'READY_TO_DISPATCH', NOW(), 0, NOW())", SHIPMENT, WAREHOUSE);
                RawSql.update(db, "SET session_replication_role = DEFAULT");
                long manager = version(db, "WAREHOUSE_MANAGER");
                long admin = version(db, "SYSTEM_ADMIN");
                long staff = version(db, "WAREHOUSE_STAFF");

                var result = flyway(postgres, MIGRATION).migrate();
                assertThat(result.migrations).extracting(m -> m.version).containsExactly(MIGRATION);

                String correction = "INSERT INTO fulfillment.delivery_outcome_correction (id, shipment_id, from_status, "
                        + "to_status, failure_reason, failure_note, reason, corrected_by, corrected_at) VALUES "
                        + "(gen_random_uuid(), ?::uuid, ?, ?, ?, ?, ?, ?::uuid, NOW())";
                RawSql.update(db, correction, SHIPMENT, "DELIVERED", "DELIVERY_FAILED", "CUSTOMER_ABSENT", null,
                        "the customer says nothing arrived", USER);
                RawSql.update(db, correction, SHIPMENT, "DELIVERY_FAILED", "DELIVERED", null, null, "found it", USER);
                RawSql.refused(db, "ck_delivery_outcome_correction_statuses", correction, SHIPMENT, "HANDED_OVER",
                        "DELIVERED", null, null, "x", USER);
                RawSql.refused(db, "ck_delivery_outcome_correction_failure", correction, SHIPMENT, "DELIVERED",
                        "DELIVERY_FAILED", null, null, "x", USER);
                RawSql.refused(db, "ck_delivery_outcome_correction_failure", correction, SHIPMENT, "DELIVERED",
                        "DELIVERY_FAILED", "OTHER", " ", "x", USER);
                RawSql.refused(db, "ck_delivery_outcome_correction_failure", correction, SHIPMENT, "DELIVERY_FAILED",
                        "DELIVERED", "LOST", null, "x", USER);
                RawSql.refused(db, "ck_delivery_outcome_correction_reason", correction, SHIPMENT, "DELIVERED",
                        "DELIVERY_FAILED", "LOST", null, " ", USER);
                RawSql.refused(db, "trg_delivery_outcome_correction_append_only",
                        "DELETE FROM fulfillment.delivery_outcome_correction");

                String pod = "INSERT INTO fulfillment.shipment_pod_file (id, shipment_id, storage_key, file_name, "
                        + "content_type, size_bytes, uploaded_by, uploaded_at) VALUES (gen_random_uuid(), ?::uuid, ?, "
                        + "'bien-ban.pdf', 'application/pdf', 10, ?::uuid, NOW())";
                RawSql.update(db, pod, SHIPMENT, "documents/2026/11/09/pod.pdf", USER);
                RawSql.refused(db, "uk_shipment_pod_file_storage_key", pod, SHIPMENT, "documents/2026/11/09/pod.pdf",
                        USER);
                RawSql.refused(db, "ck_shipment_pod_file_key", pod, SHIPMENT, "images/2026/11/09/pod.jpg", USER);

                assertThat(RawSql.number(db, "SELECT count(*) FROM ordering.order_status_transition WHERE from_status "
                        + "= 'DELIVERED' AND to_status = 'DELIVERY_FAILED'")).isOne();
                assertThat(RawSql.texts(db, "SELECT r.code FROM identity.role_permission rp JOIN identity.app_role r "
                        + "ON r.id = rp.role_id JOIN identity.permission p ON p.id = rp.permission_id "
                        + "WHERE p.code = 'fulfillment-delivery-corrections:UPDATE' ORDER BY 1"))
                        .containsExactly("SYSTEM_ADMIN", "WAREHOUSE_MANAGER");
                assertThat(version(db, "WAREHOUSE_MANAGER")).isEqualTo(manager + 1);
                assertThat(version(db, "SYSTEM_ADMIN")).isEqualTo(admin + 1);
                assertThat(version(db, "WAREHOUSE_STAFF")).isEqualTo(staff);
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
