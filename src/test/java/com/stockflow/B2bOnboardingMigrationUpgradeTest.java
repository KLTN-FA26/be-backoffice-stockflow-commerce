package com.stockflow;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V20261014000100 on a database that already holds customers: the develop schema before SCRUM-450,
 * populated with a legacy INACTIVE customer, an ACTIVE one, and the open invitation the SCRUM-429
 * branch kept in {@code password_hash}. Then the constraints the migration adds, on real PostgreSQL.
 */
class B2bOnboardingMigrationUpgradeTest {

    private static final String BEFORE = "20261013000600";
    private static final String DIGEST = "0123456789abcdef".repeat(4);

    @Test
    void upgradeCarriesBranchInvitationsMarksOnlyThoseCustomersPendingAndInventsNoTaxCodes() throws Exception {
        try (var pg = new PostgreSQLContainer<>("postgres:16-alpine")) {
            pg.start();
            Flyway.configure().dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
                    .locations("classpath:db/migration").target(BEFORE).load().migrate();
            try (Connection connection = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
                 Statement sql = connection.createStatement()) {
                long issued = Instant.parse("2026-10-12T01:00:00Z").getEpochSecond();
                long expires = Instant.parse("2026-10-15T01:00:00Z").getEpochSecond();
                UUID invitedUser = user(sql, "invited@example.test", "'{portal-invitation}" + issued + ":" + expires + ":" + DIGEST + "'");
                UUID oldUser = user(sql, "old@example.test", "'{bcrypt}$2a$10$legacy'");
                UUID activeUser = user(sql, "active@example.test", "'{bcrypt}$2a$10$active'");
                UUID invitedCustomer = customer(sql, invitedUser, "invited@example.test", "INACTIVE");
                UUID closedCustomer = customer(sql, oldUser, "old@example.test", "INACTIVE");
                UUID unlinkedCustomer = customer(sql, null, "unlinked@example.test", "INACTIVE");
                UUID activeCustomer = customer(sql, activeUser, "active@example.test", "ACTIVE");
                long salesVersion = roleVersion(sql, "SALES_STAFF");
                long customerRoleVersion = roleVersion(sql, "CUSTOMER");

                var migrated = Flyway.configure().dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
                        .locations("classpath:db/migration").load();
                assertThat(migrated.migrate().migrations).extracting(m -> m.version).contains("20261014000100");
                migrated.validate();

                assertThat(text(sql, "select status from customer.customer where id='" + invitedCustomer + "'"))
                        .isEqualTo("PENDING_ACTIVATION");
                for (UUID untouched : new UUID[]{closedCustomer, unlinkedCustomer}) {
                    assertThat(text(sql, "select status from customer.customer where id='" + untouched + "'"))
                            .isEqualTo("INACTIVE");
                }
                assertThat(text(sql, "select status from customer.customer where id='" + activeCustomer + "'"))
                        .isEqualTo("ACTIVE");
                assertThat(number(sql, "select count(*) from customer.customer where tax_code is not null")).isZero();

                assertThat(text(sql, "select coalesce(password_hash,'<null>') from identity.app_user where id='" + invitedUser + "'"))
                        .isEqualTo("<null>");
                assertThat(text(sql, "select password_hash from identity.app_user where id='" + oldUser + "'"))
                        .isEqualTo("{bcrypt}$2a$10$legacy");
                assertThat(text(sql, """
                        select token_digest || '|' || extract(epoch from issued_at)::bigint || '|'
                               || extract(epoch from expires_at)::bigint || '|' || coalesce(delivery_status, '-')
                        from identity.portal_invitation where user_id='""" + invitedUser + "' and consumed_at is null and revoked_at is null"))
                        .isEqualTo(DIGEST + "|" + issued + "|" + expires + "|-");
                assertThat(number(sql, "select count(*) from identity.portal_invitation")).isEqualTo(1);

                assertThat(roleVersion(sql, "SALES_STAFF")).isEqualTo(salesVersion + 1);
                assertThat(roleVersion(sql, "CUSTOMER")).isEqualTo(customerRoleVersion);
                assertThat(number(sql, """
                        select count(*) from identity.role_permission rp
                        join identity.app_role r on r.id = rp.role_id join identity.permission p on p.id = rp.permission_id
                        where p.code = 'customer-customers:CREATE'""")).isEqualTo(3);

                assertConstraints(sql, invitedUser, activeUser);
            }
        }
    }

    /** Every rule V20261014000100 states, refused or accepted by the database itself. */
    private static void assertConstraints(Statement sql, UUID invitedUser, UUID activeUser) throws SQLException {
        sql.executeUpdate("update customer.customer set tax_code='0312345678' where email='active@example.test'");
        refused(sql, "a second ACTIVE customer with the tax code",
                "insert into customer.customer(id,full_name,email,status,tax_code,version,created_at) values"
                        + " (gen_random_uuid(),'Dup','dup@example.test','ACTIVE','0312345678',0,now())");
        refused(sql, "a PENDING customer with a live tax code",
                "insert into customer.customer(id,user_id,full_name,email,status,tax_code,version,created_at) values"
                        + " (gen_random_uuid(),'" + user(sql, "p2@example.test", "null") + "','Dup','p2@example.test',"
                        + "'PENDING_ACTIVATION','0312345678',0,now())");
        sql.executeUpdate("insert into customer.customer(id,full_name,email,status,tax_code,version,created_at) values"
                + " (gen_random_uuid(),'Closed twin','closed-twin@example.test','INACTIVE','0312345678',0,now())");
        sql.executeUpdate("insert into customer.customer(id,full_name,email,status,tax_code,version,created_at) values"
                + " (gen_random_uuid(),'Blocked twin','blocked-twin@example.test','BLOCKED','0312345678',0,now())");
        refused(sql, "a malformed tax code",
                "update customer.customer set tax_code='031234567' where email='active@example.test'");
        refused(sql, "an unnormalised dependent-unit code",
                "update customer.customer set tax_code='0312345678001' where email='active@example.test'");
        sql.executeUpdate("update customer.customer set tax_code='0312345678-001' where email='active@example.test'");
        refused(sql, "a pending customer without an account",
                "insert into customer.customer(id,full_name,email,status,version,created_at) values"
                        + " (gen_random_uuid(),'No account','none@example.test','PENDING_ACTIVATION',0,now())");
        refused(sql, "an unknown status",
                "update customer.customer set status='SUSPENDED' where email='active@example.test'");

        String open = "insert into identity.portal_invitation(id,user_id,token_digest,issued_at,expires_at) values"
                + " (gen_random_uuid(),'" + invitedUser + "',";
        refused(sql, "a second open invitation for one account",
                open + "'" + "f".repeat(64) + "',now(),now()+interval '1 day')");
        refused(sql, "a raw token instead of a digest",
                "insert into identity.portal_invitation(id,user_id,token_digest,issued_at,expires_at) values"
                        + " (gen_random_uuid(),'" + activeUser + "','" + "Z".repeat(64) + "',now(),now()+interval '1 day')");
        refused(sql, "an invitation that expires before it is issued",
                "insert into identity.portal_invitation(id,user_id,token_digest,issued_at,expires_at) values"
                        + " (gen_random_uuid(),'" + activeUser + "','" + "e".repeat(64) + "',now(),now())");
        refused(sql, "consumed and revoked at once",
                "update identity.portal_invitation set consumed_at=issued_at+interval '1 minute',"
                        + " revoked_at=issued_at+interval '1 minute' where user_id='" + invitedUser + "'");
        refused(sql, "consumed after it expired",
                "update identity.portal_invitation set consumed_at=expires_at where user_id='" + invitedUser + "'");
        refused(sql, "a delivery outcome without its time",
                "update identity.portal_invitation set delivery_status='SENT' where user_id='" + invitedUser + "'");
        sql.executeUpdate("update identity.portal_invitation set revoked_at=issued_at+interval '1 minute'"
                + " where user_id='" + invitedUser + "'");
        sql.executeUpdate(open + "'" + "f".repeat(64) + "',now(),now()+interval '1 day')");
        refused(sql, "a reused token digest",
                "insert into identity.portal_invitation(id,user_id,token_digest,issued_at,expires_at,revoked_at) values"
                        + " (gen_random_uuid(),'" + activeUser + "','" + "f".repeat(64)
                        + "',now(),now()+interval '1 day',now())");
    }

    private static UUID user(Statement sql, String email, String passwordHashLiteral) throws SQLException {
        UUID id = UUID.randomUUID();
        sql.executeUpdate("insert into identity.app_user(id,username,email,password_hash,status,version,created_at)"
                + " values ('" + id + "','" + email + "','" + email + "'," + passwordHashLiteral + ",'ACTIVE',0,now())");
        return id;
    }

    private static UUID customer(Statement sql, UUID userId, String email, String status) throws SQLException {
        UUID id = UUID.randomUUID();
        sql.executeUpdate("insert into customer.customer(id,user_id,full_name,email,status,version,created_at) values ('"
                + id + "'," + (userId == null ? "null" : "'" + userId + "'") + ",'Co','" + email + "','" + status
                + "',0,now())");
        return id;
    }

    private static void refused(Statement sql, String rule, String statement) {
        assertThatThrownBy(() -> sql.executeUpdate(statement)).as(rule).isInstanceOf(SQLException.class);
    }

    private static long roleVersion(Statement sql, String code) throws SQLException {
        return number(sql, "select version from identity.app_role where code='" + code + "'");
    }

    private static long number(Statement sql, String query) throws SQLException {
        try (var row = sql.executeQuery(query)) {
            row.next();
            return row.getLong(1);
        }
    }

    private static String text(Statement sql, String query) throws SQLException {
        try (var row = sql.executeQuery(query)) {
            row.next();
            return row.getString(1);
        }
    }
}
