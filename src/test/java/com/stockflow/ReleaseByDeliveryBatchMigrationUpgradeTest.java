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
 * V20261116000300 on the real upgrade path (team plan 4B-8): an order is released, picked and handed over by delivery
 * batch. The pick of an order was unique ({@code uk_fulfillment_pick_order}); it becomes unique per order and batch
 * ({@code uk_fulfillment_pick_order_batch}, NULLs not distinct), so an order that already has its one pick keeps it and may
 * get one more for a batch released later, but never two of one batch, nor two without a batch. The release permission is
 * added with the grants of its roles, and a role that already holds it (cluster 2's migration adds the same row) is not
 * touched again.
 */
class ReleaseByDeliveryBatchMigrationUpgradeTest {

    private static final String BEFORE = "20261116000200";
    private static final String MIGRATION = "20261116000300";
    private static final String CUSTOMER = "00000000-0000-4000-8000-0000000000c1";
    private static final String ORDER = "00000000-0000-4000-8000-0000000000d1";
    private static final String WAREHOUSE = "00000000-0000-4000-8000-0000000000e1";

    @Test
    void anOrderKeepsItsPickAndMayGetOnePerBatch() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            flyway(postgres, BEFORE).migrate();
            try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())) {
                seed(db);
                long managerBefore = version(db, "WAREHOUSE_MANAGER");
                long adminBefore = version(db, "SYSTEM_ADMIN");
                long staffBefore = version(db, "WAREHOUSE_STAFF");

                var result = flyway(postgres, MIGRATION).migrate();
                assertThat(result.migrations).extracting(m -> m.version).containsExactly(MIGRATION);

                assertThat(RawSql.number(db, "SELECT count(*) FROM fulfillment.pick WHERE order_id = '" + ORDER + "'"))
                        .as("the order's pick is kept").isEqualTo(1);
                assertThat(RawSql.texts(db, "SELECT table_name FROM information_schema.tables WHERE table_schema = "
                        + "'ordering' AND table_name LIKE 'delivery_batch_%' ORDER BY 1"))
                        .contains("delivery_batch_hold", "delivery_batch_release", "delivery_batch_window_change");

                RawSql.refused(db, "uk_fulfillment_pick_order_batch", pick("00000000-0000-4000-8000-0000000000f2", null));
                String batch = null;
                RawSql.update(db, "SET session_replication_role = replica");
                batch = seedBatch(db);
                RawSql.update(db, "INSERT INTO ordering.delivery_batch_release (delivery_batch_id, order_id, seq, "
                        + "warehouse_id, released_at, released_by) VALUES ('" + batch + "', '" + ORDER + "', 1, '"
                        + WAREHOUSE + "', NOW(), '00000000-0000-4000-8000-0000000000a1')");
                RawSql.update(db, "SET session_replication_role = origin");
                assertThat(RawSql.update(db, pick("00000000-0000-4000-8000-0000000000f3", batch)))
                        .as("a second pick, for a released batch").isEqualTo(1);
                RawSql.refused(db, "uk_fulfillment_pick_order_batch", pick("00000000-0000-4000-8000-0000000000f4", batch));
                RawSql.refused(db, "uk_fulfillment_pick_order_batch", pick("00000000-0000-4000-8000-0000000000f5", null));

                assertThat(RawSql.number(db, "SELECT count(*) FROM identity.role_permission rp JOIN identity.permission p "
                        + "ON p.id = rp.permission_id WHERE p.code = 'sales-order-releases:UPDATE'"))
                        .as("granted to the warehouse manager and the administrator").isEqualTo(2);
                assertThat(version(db, "WAREHOUSE_MANAGER")).as("it held the permission already: not touched again")
                        .isEqualTo(managerBefore);
                assertThat(version(db, "SYSTEM_ADMIN")).as("it gained the grant").isGreaterThan(adminBefore);
                assertThat(version(db, "WAREHOUSE_STAFF")).isEqualTo(staffBefore);
            }
        }
    }

    private static String pick(String id, String batch) {
        return "INSERT INTO fulfillment.pick (id, order_id, order_number, warehouse_id, status, created_at"
                + (batch == null ? "" : ", delivery_batch_id") + ") VALUES ('" + id + "', '" + ORDER + "', 'SO-UPG-8', '"
                + WAREHOUSE + "', 'PICKING', NOW()" + (batch == null ? "" : ", '" + batch + "'") + ")";
    }

    /** A signed contract's batch of the order, written with triggers and foreign keys off. */
    private static String seedBatch(Connection db) throws SQLException {
        String contract = "00000000-0000-4000-8000-0000000000b1";
        String batch = "00000000-0000-4000-8000-0000000000b2";
        RawSql.update(db, "UPDATE ordering.customer_order SET shipping_warehouse_id = '" + WAREHOUSE + "', "
                + "shipping_warehouse_chosen_at = NOW(), shipping_warehouse_chosen_by = '"
                + "00000000-0000-4000-8000-0000000000a1' WHERE id = '" + ORDER + "'");
        RawSql.update(db, "INSERT INTO ordering.sales_contract (id, contract_number, order_id, status, seller_name, "
                + "seller_tax_code, seller_address, seller_representative, seller_representative_title, buyer_name, "
                + "buyer_address, buyer_representative, buyer_representative_title, free_sample_rounds, sent_at, sent_by, "
                + "sent_document_key, signed_at, "
                + "signed_by, signed_on, signed_file_key, signed_file_name) VALUES ('" + contract + "', 'HD-UPG-8', '"
                + ORDER + "', 'SIGNED', 'S', '0101', 'A', 'A', 'D', 'B', 'B', 'B', 'D', 2, NOW(), "
                + "'00000000-0000-4000-8000-0000000000a1', 'documents/hd.pdf', NOW(), "
                + "'00000000-0000-4000-8000-0000000000a1', CURRENT_DATE, 'k', 'f')");
        RawSql.update(db, "INSERT INTO ordering.delivery_batch (id, contract_id, seq, window_start, window_end) VALUES ('"
                + batch + "', '" + contract + "', 1, CURRENT_DATE + 1, CURRENT_DATE + 2)");
        return batch;
    }

    private static long version(Connection db, String role) throws SQLException {
        return RawSql.number(db, "SELECT version FROM identity.app_role WHERE code = ?", role);
    }

    private static void seed(Connection db) throws SQLException {
        RawSql.update(db, "SET session_replication_role = replica");
        RawSql.update(db, "INSERT INTO identity.app_user (id, username, email, password_hash, status, version, "
                + "created_at) VALUES ('00000000-0000-4000-8000-0000000000a1', 'rel-upg', 'rel@example.com', '!', "
                + "'ACTIVE', 0, NOW())");
        RawSql.update(db, "INSERT INTO customer.customer (id, full_name, email, status, version, created_at) VALUES ('"
                + CUSTOMER + "', 'Rel', 'rel1@example.com', 'ACTIVE', 0, NOW())");
        RawSql.update(db, "INSERT INTO warehouse.warehouse (id, name, status, prefix, address, map_unit, map_width, "
                + "map_height, version, created_at) VALUES ('" + WAREHOUSE + "', 'Upg', 'ACTIVE', 'UPG8', 'A', 'M', 50, "
                + "50, 0, NOW())");
        RawSql.update(db, "INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, "
                + "total_amount, currency, placed_at, created_at, contract_signed_at) VALUES ('" + ORDER
                + "', 'SO-UPG-8', '" + CUSTOMER + "', gen_random_uuid(), 'CONFIRMED', 1000, 'VND', NOW(), NOW(), NOW())");
        RawSql.update(db, pick("00000000-0000-4000-8000-0000000000f1", null).replace("', 'PICKING'", "', 'PICKING'"));
        // The permission is already there, held by the warehouse manager - as cluster 2's own migration would leave it.
        RawSql.update(db, "INSERT INTO identity.permission (id, code, resource, action, description, version, created_at, "
                + "created_by) VALUES (gen_random_uuid(), 'sales-order-releases:UPDATE', 'sales-order-releases', "
                + "'UPDATE', 'Release an order', 0, NOW(), 'flyway')");
        RawSql.update(db, "INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, "
                + "created_by) SELECT gen_random_uuid(), r.id, p.id, 0, NOW(), 'flyway' FROM identity.app_role r, "
                + "identity.permission p WHERE r.code = 'WAREHOUSE_MANAGER' AND p.code = 'sales-order-releases:UPDATE'");
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
