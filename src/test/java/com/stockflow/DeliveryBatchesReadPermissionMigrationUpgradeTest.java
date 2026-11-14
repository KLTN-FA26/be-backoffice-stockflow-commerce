package com.stockflow;

import com.stockflow.support.RawSql;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V20261124000400 on the real upgrade path (WF4 BE-13; kltn-docs 17 §4.2, §4.5): the delivery schedule gets its own read
 * permission, {@code sales-order-delivery-batches:READ}, granted to everyone who read the schedule with the contract
 * ({@code sales-contracts:READ}) and to whoever releases ({@code sales-order-releases:UPDATE}) - the warehouse manager -
 * who does not get the contract. Generic over roles, so one created at run time follows too; a role holding neither
 * (the customer, warehouse staff) gets nothing and its authorisation version does not move.
 */
class DeliveryBatchesReadPermissionMigrationUpgradeTest {

    private static final String BEFORE = "20261124000300";
    private static final String MIGRATION = "20261124000400";
    private static final String CODE = "sales-order-delivery-batches:READ";

    @Test
    void whoeverReadTheScheduleOrReleasesGainsItAndNobodyElse() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            flyway(postgres, BEFORE).migrate();
            try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())) {
                // A role made at run time with each of the two keys, and one with a permission unrelated to either.
                role(db, "RT_CONTRACT_READER", "sales-contracts:READ");
                role(db, "RT_RELEASER", "sales-order-releases:UPDATE");
                role(db, "RT_OTHER", "sales-orders:READ");
                long managerBefore = version(db, "WAREHOUSE_MANAGER");
                long staffBefore = version(db, "WAREHOUSE_STAFF");
                long customerBefore = version(db, "CUSTOMER");
                long otherBefore = version(db, "RT_OTHER");
                long contractReaderBefore = version(db, "RT_CONTRACT_READER");
                assertThat(holders(db, CODE)).as("nothing grants it before").isEmpty();
                assertThat(holders(db, "sales-contracts:READ")).as("the warehouse manager never read the contract")
                        .doesNotContain("WAREHOUSE_MANAGER");

                var result = flyway(postgres, MIGRATION).migrate();
                assertThat(result.migrations).extracting(m -> m.version).containsExactly(MIGRATION);

                assertThat(RawSql.text(db, "SELECT resource || '/' || action FROM identity.permission WHERE code = ?", CODE))
                        .isEqualTo("sales-order-delivery-batches/READ");
                List<String> holders = holders(db, CODE);
                assertThat(holders).as("the contract's readers, the releasers, the administrator")
                        .contains("SALES_STAFF", "SALES_MANAGER", "ACCOUNTANT", "WAREHOUSE_MANAGER", "SYSTEM_ADMIN",
                                "RT_CONTRACT_READER", "RT_RELEASER")
                        .doesNotContain("CUSTOMER", "WAREHOUSE_STAFF", "RT_OTHER");
                // Exactly the union of the two keys, never more.
                assertThat(RawSql.number(db, "SELECT count(DISTINCT rp.role_id) FROM identity.role_permission rp JOIN "
                        + "identity.permission p ON p.id = rp.permission_id WHERE p.code IN ('sales-contracts:READ', "
                        + "'sales-order-releases:UPDATE')")).isEqualTo(holders.size());
                // The warehouse manager still does not read the contract.
                assertThat(holders(db, "sales-contracts:READ")).doesNotContain("WAREHOUSE_MANAGER");

                // ADR-0008: the roles that gained a grant move their version; the others do not.
                assertThat(version(db, "WAREHOUSE_MANAGER")).isEqualTo(managerBefore + 1);
                assertThat(version(db, "RT_CONTRACT_READER")).isEqualTo(contractReaderBefore + 1);
                assertThat(version(db, "WAREHOUSE_STAFF")).isEqualTo(staffBefore);
                assertThat(version(db, "CUSTOMER")).isEqualTo(customerBefore);
                assertThat(version(db, "RT_OTHER")).isEqualTo(otherBefore);
            }
        }
    }

    private static List<String> holders(Connection db, String permission) throws SQLException {
        return RawSql.texts(db, "SELECT r.code FROM identity.role_permission rp JOIN identity.app_role r ON r.id = "
                + "rp.role_id JOIN identity.permission p ON p.id = rp.permission_id WHERE p.code = ? ORDER BY 1",
                permission);
    }

    private static void role(Connection db, String code, String permission) throws SQLException {
        RawSql.update(db, "INSERT INTO identity.app_role (id, code, name, version, created_at, is_system, data_scope) "
                + "VALUES (gen_random_uuid(), '" + code + "', '" + code + "', 0, NOW(), FALSE, 'ALL')");
        RawSql.update(db, "INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at) "
                + "SELECT gen_random_uuid(), r.id, p.id, 0, NOW() FROM identity.app_role r, identity.permission p "
                + "WHERE r.code = '" + code + "' AND p.code = '" + permission + "'");
    }

    private static long version(Connection db, String role) throws SQLException {
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
