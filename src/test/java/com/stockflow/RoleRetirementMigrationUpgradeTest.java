package com.stockflow;

import com.stockflow.support.RawSql;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V20261115000200 and V20261115000300 on the real upgrade path: a database migrated to the migration before them,
 * holding what three retired roles left behind - an order coordinator, an inventory planner with no warehouse, one with
 * a warehouse of their own, someone holding both, a purchasing clerk who is nothing else and one who is also warehouse
 * staff, each with a live session (and a long expired one, and one already revoked), a grant that no seed names added
 * to the coordinator - then upgraded, one migration at a time.
 *
 * <p>Proves the people are looked after (the coordinator becomes Sales Staff, a planner a warehouse manager of every
 * active warehouse unless they had one, a purchasing clerk with nothing else is disabled with the reason on record and
 * the others keep their other role), their live sessions end with the reason that fits and nobody else's session or
 * history is touched, every grant of the roles goes - including the one no list names - with the version of the roles
 * that lost or gained a grant advanced (ADR-0008), what the roles did is held by the role that took it, the System
 * Admin holds every permission row, and the three roles are gone.</p>
 */
class RoleRetirementMigrationUpgradeTest {

    private static final String BEFORE = "20261115000100";
    private static final String COORDINATOR_PLANNER = "20261115000200";
    private static final String PROCUREMENT = "20261115000300";

    @Test
    void upgradeRetiresTheThreeRolesAndLooksAfterTheirHolders() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            flyway(postgres, BEFORE).migrate();
            try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())) {
                UUID coordinator = user(db, "coordinator", "ORDER_COORDINATOR");
                UUID planner = user(db, "planner", "INVENTORY_PLANNER");
                UUID plannerWithBin = user(db, "plannerbin", "INVENTORY_PLANNER");
                UUID both = user(db, "both", "ORDER_COORDINATOR", "INVENTORY_PLANNER");
                UUID buyer = user(db, "buyer", "PROCUREMENT_STAFF");
                UUID buyerAndClerk = user(db, "buyerclerk", "PROCUREMENT_STAFF", "WAREHOUSE_STAFF");
                UUID bystander = user(db, "bystander", "SALES_STAFF");
                // Two active warehouses of the test's own: this database has the migrations' data only.
                UUID warehouse = warehouse(db, "RRA");
                warehouse(db, "RRB");
                RawSql.update(db, "INSERT INTO identity.user_warehouse (id, user_id, warehouse_id, version, created_at) "
                        + "VALUES (gen_random_uuid(), ?, ?, 0, NOW())", plannerWithBin, warehouse);
                long activeWarehouses = RawSql.number(db,
                        "SELECT count(*) FROM warehouse.warehouse WHERE status = 'ACTIVE'");

                // Sessions: live for every holder and the bystander; one long expired, one already revoked.
                for (UUID holder : List.of(coordinator, planner, plannerWithBin, both, buyer, buyerAndClerk, bystander)) {
                    session(db, holder, "live-" + holder, "NOW() + interval '1 hour'", null);
                }
                session(db, coordinator, "expired", "NOW() - interval '1 day'", null);
                session(db, coordinator, "revoked", "NOW() + interval '1 hour'", "LOGOUT");

                // A grant no seed names, added to the coordinator and to the purchasing clerk later by some migration.
                RawSql.update(db, "INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at) "
                        + "SELECT gen_random_uuid(), r.id, p.id, 0, NOW() FROM identity.app_role r, identity.permission p "
                        + "WHERE r.code IN ('ORDER_COORDINATOR', 'PROCUREMENT_STAFF') AND p.code = 'platform-actuator:READ' "
                        + "ON CONFLICT DO NOTHING");
                assertThat(grants(db, "ORDER_COORDINATOR")).as("the extra grant is there").contains("platform-actuator:READ");
                List<String> buyerGrants = grants(db, "PROCUREMENT_STAFF");

                long managerVersion = version(db, "WAREHOUSE_MANAGER");
                long salesVersion = version(db, "SALES_STAFF");
                long adminVersion = version(db, "SYSTEM_ADMIN");
                long statusChanges = RawSql.number(db, "SELECT count(*) FROM identity.user_status_change");
                List<String> sessionsOfBystander = RawSql.texts(db,
                        "SELECT id::text FROM identity.user_session WHERE user_id = ? AND revoked_at IS NULL", bystander);

                flyway(postgres, COORDINATOR_PLANNER).migrate();

                // --- 5-7: the roles are gone, so is every grant and every holding of them
                assertThat(RawSql.number(db, "SELECT count(*) FROM identity.app_role WHERE code IN "
                        + "('ORDER_COORDINATOR', 'INVENTORY_PLANNER')")).isZero();
                assertThat(roles(db, coordinator)).containsExactly("SALES_STAFF");
                assertThat(roles(db, planner)).containsExactly("WAREHOUSE_MANAGER");
                assertThat(roles(db, plannerWithBin)).containsExactly("WAREHOUSE_MANAGER");
                assertThat(roles(db, both)).containsExactlyInAnyOrder("SALES_STAFF", "WAREHOUSE_MANAGER");
                assertThat(roles(db, bystander)).containsExactly("SALES_STAFF");

                // A planner assigned nowhere would see nothing as a warehouse manager: every active warehouse. One who
                // had a warehouse keeps just it.
                assertThat(RawSql.number(db, "SELECT count(*) FROM identity.user_warehouse WHERE user_id = ?", planner))
                        .isEqualTo(activeWarehouses).isPositive();
                assertThat(RawSql.texts(db, "SELECT warehouse_id::text FROM identity.user_warehouse WHERE user_id = ?",
                        plannerWithBin)).containsExactly(warehouse.toString());
                assertThat(RawSql.number(db, "SELECT count(*) FROM identity.user_warehouse WHERE user_id = ?", coordinator))
                        .as("a coordinator becomes Sales, whose scope is not warehouse").isZero();

                // Live sessions of the holders end as ROLE_CHANGED; the expired and the already revoked keep what they
                // had; a bystander's session is not touched.
                for (UUID holder : List.of(coordinator, planner, plannerWithBin, both)) {
                    assertThat(RawSql.texts(db, "SELECT revoked_reason FROM identity.user_session WHERE user_id = ? "
                            + "AND user_agent = ?", holder, "live-" + holder)).containsExactly("ROLE_CHANGED");
                }
                assertThat(RawSql.texts(db, "SELECT coalesce(revoked_reason, '-') FROM identity.user_session "
                        + "WHERE user_agent = 'expired'")).containsExactly("-");
                assertThat(RawSql.texts(db, "SELECT revoked_reason FROM identity.user_session WHERE user_agent = 'revoked'"))
                        .containsExactly("LOGOUT");
                assertThat(RawSql.texts(db, "SELECT id::text FROM identity.user_session WHERE user_id = ? "
                        + "AND revoked_at IS NULL", bystander)).isEqualTo(sessionsOfBystander);
                assertThat(RawSql.texts(db, "SELECT revoked_reason FROM identity.user_session WHERE user_id IN (?, ?) "
                        + "AND user_agent LIKE 'live-%'", buyer, buyerAndClerk)).containsOnlyNulls();

                // The work is held by the role that took it, and the role versions moved for those who gained.
                assertThat(grants(db, "WAREHOUSE_MANAGER")).contains("sales-order-releases:UPDATE",
                        "fulfillment-delivery-outcomes:APPROVE", "sales-orders:READ", "sales-orders:VIEW_PAGE",
                        "fulfillment-task-assignment:CREATE", "fulfillment-task-assignment:UPDATE")
                        .as("the planner's exports guarded nothing and went with the role")
                        .doesNotContain("inventory-stock-items:EXPORT", "inventory-stock-movements:EXPORT");
                assertThat(grants(db, "SALES_STAFF")).contains("sales-order-holds:UPDATE",
                        "fulfillment-load-estimates:READ", "fulfillment-load-estimates:CREATE")
                        .doesNotContain("sales-order-releases:UPDATE", "fulfillment-task-assignment:CREATE");
                assertThat(version(db, "WAREHOUSE_MANAGER")).isGreaterThan(managerVersion);
                assertThat(version(db, "SALES_STAFF")).isGreaterThan(salesVersion);
                assertThat(version(db, "SYSTEM_ADMIN")).isGreaterThan(adminVersion);

                // sales-orders:APPROVE and the permissions no code declares are gone with their grants.
                assertThat(RawSql.number(db, "SELECT count(*) FROM identity.permission WHERE code = "
                        + "'sales-orders:APPROVE' OR resource IN ('sales-carts', 'payment-payments', "
                        + "'payment-cod-remittances', 'warehouse-replenishment')")).isZero();
                assertThat(RawSql.number(db, "SELECT count(*) FROM identity.permission WHERE code IN "
                        + "('sales-order-releases:UPDATE', 'sales-order-holds:UPDATE')")).isEqualTo(2);

                // The System Admin holds every permission row (00 §2.5.3).
                assertThat(RawSql.number(db, "SELECT count(*) FROM identity.permission p WHERE NOT EXISTS (SELECT 1 FROM "
                        + "identity.role_permission rp JOIN identity.app_role r ON r.id = rp.role_id "
                        + "WHERE rp.permission_id = p.id AND r.code = 'SYSTEM_ADMIN')")).isZero();

                long managerBefore5_8 = version(db, "WAREHOUSE_MANAGER");
                long staffBefore5_8 = version(db, "WAREHOUSE_STAFF");

                flyway(postgres, PROCUREMENT).migrate();

                // --- 5-8: PROCUREMENT_STAFF
                assertThat(RawSql.number(db, "SELECT count(*) FROM identity.app_role WHERE code = 'PROCUREMENT_STAFF'"))
                        .isZero();
                assertThat(buyerGrants).as("what the role held is also held by the warehouse's own roles")
                        .allSatisfy(permission -> assertThat(
                                grants(db, "WAREHOUSE_STAFF").contains(permission)
                                        || grants(db, "WAREHOUSE_MANAGER").contains(permission)
                                        || permission.equals("platform-actuator:READ"))
                                .as(permission).isTrue());
                // A clerk of purchasing and nothing else is disabled, with the reason on record (QD-13) ...
                assertThat(RawSql.text(db, "SELECT status FROM identity.app_user WHERE id = ?", buyer))
                        .isEqualTo("DISABLED");
                assertThat(roles(db, buyer)).isEmpty();
                assertThat(RawSql.text(db, "SELECT action || '|' || from_status || '|' || to_status || '|' || actor_username "
                        + "|| '|' || reason FROM identity.user_status_change WHERE user_id = ?", buyer))
                        .isEqualTo("DISABLE|ACTIVE|DISABLED|flyway|Purchasing from suppliers was removed: the role "
                                + "PROCUREMENT_STAFF no longer exists");
                assertThat(RawSql.texts(db, "SELECT revoked_reason FROM identity.user_session WHERE user_id = ?", buyer))
                        .containsExactly("ACCOUNT_DISABLED");
                // ... one with another role keeps it and stays active.
                assertThat(RawSql.text(db, "SELECT status FROM identity.app_user WHERE id = ?", buyerAndClerk))
                        .isEqualTo("ACTIVE");
                assertThat(roles(db, buyerAndClerk)).containsExactly("WAREHOUSE_STAFF");
                assertThat(RawSql.texts(db, "SELECT revoked_reason FROM identity.user_session WHERE user_id = ?",
                        buyerAndClerk)).containsExactly("ROLE_CHANGED");
                assertThat(RawSql.number(db, "SELECT count(*) FROM identity.user_status_change")).as("exactly one new row")
                        .isEqualTo(statusChanges + 1);
                // Nobody else was touched: the others' live sessions, the roles that gained nothing.
                assertThat(RawSql.texts(db, "SELECT id::text FROM identity.user_session WHERE user_id = ? "
                        + "AND revoked_at IS NULL", bystander)).isEqualTo(sessionsOfBystander);
                assertThat(version(db, "WAREHOUSE_MANAGER")).isEqualTo(managerBefore5_8);
                assertThat(version(db, "WAREHOUSE_STAFF")).isEqualTo(staffBefore5_8);

                // The roles that are left: the seven staff roles of 00 §2.1 and the customer's.
                assertThat(RawSql.texts(db, "SELECT code FROM identity.app_role ORDER BY code")).containsExactly(
                        "ACCOUNTANT", "CUSTOMER", "PRODUCTION_COORDINATOR", "SALES_MANAGER", "SALES_STAFF",
                        "SYSTEM_ADMIN", "WAREHOUSE_MANAGER", "WAREHOUSE_STAFF");
            }
        }
    }

    private static UUID user(Connection db, String name, String... roles) throws Exception {
        UUID id = UUID.randomUUID();
        String unique = name + "-" + id.toString().substring(0, 8);
        RawSql.update(db, "INSERT INTO identity.app_user (id, username, email, status, created_at) "
                + "VALUES (?, ?, ?, 'ACTIVE', NOW())", id, unique, unique + "@example.com");
        for (String role : roles) {
            RawSql.update(db, "INSERT INTO identity.user_role (id, user_id, role_id, version, created_at) SELECT "
                    + "gen_random_uuid(), ?, id, 0, NOW() FROM identity.app_role WHERE code = ?", id, role);
        }
        return id;
    }

    private static UUID warehouse(Connection db, String prefix) throws Exception {
        UUID id = UUID.randomUUID();
        RawSql.update(db, "INSERT INTO warehouse.warehouse (id, name, status, prefix, address, map_unit, map_width, "
                + "map_height, version, created_at) VALUES (?, 'Retirement test', 'ACTIVE', ?, 'Test address', 'M', 50, "
                + "50, 0, NOW())", id, prefix);
        return id;
    }

    private static void session(Connection db, UUID user, String tag, String expiresAt, String revokedReason)
            throws Exception {
        RawSql.update(db, "INSERT INTO identity.user_session (id, user_id, user_agent, issued_at, expires_at, created_at, "
                + "revoked_at, revoked_reason) VALUES (gen_random_uuid(), ?, ?, NOW() - interval '2 days', " + expiresAt
                + ", NOW(), " + (revokedReason == null ? "NULL" : "NOW()") + ", ?)", user, tag, revokedReason);
    }

    private static List<String> roles(Connection db, UUID user) throws Exception {
        return RawSql.texts(db, "SELECT r.code FROM identity.user_role ur JOIN identity.app_role r ON r.id = ur.role_id "
                + "WHERE ur.user_id = ? ORDER BY r.code", user);
    }

    private static List<String> grants(Connection db, String role) throws Exception {
        return RawSql.texts(db, "SELECT p.code FROM identity.role_permission rp JOIN identity.app_role r "
                + "ON r.id = rp.role_id JOIN identity.permission p ON p.id = rp.permission_id WHERE r.code = ? "
                + "ORDER BY p.code", role);
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
