package com.stockflow;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

import static com.stockflow.support.RawSql.number;
import static com.stockflow.support.RawSql.refused;
import static com.stockflow.support.RawSql.text;
import static com.stockflow.support.RawSql.update;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * V20261015000100 on a database that already holds customers: the develop schema before SCRUM-450,
 * populated with a legacy INACTIVE customer, an ACTIVE one, and the open invitation the SCRUM-429
 * branch kept in {@code password_hash}. Then the constraints the migration adds, on real PostgreSQL.
 */
class B2bOnboardingMigrationUpgradeTest {

    /**
     * The newest migration on develop when this branch was last merged with it (0f18f24): the real upgrade path, and a
     * window holding only this migration - develop's migrations (V20261014000400..001200: inbox, stock alerts, pick
     * confirmation, product follow-ups, planner grants) move role versions of their own and must not count here.
     */
    private static final String BEFORE = "20261014001200";
    private static final String DIGEST = "0123456789abcdef".repeat(4);

    @Test
    void upgradeCarriesBranchInvitationsMarksOnlyThoseCustomersPendingAndInventsNoTaxCodes() throws Exception {
        try (var pg = new PostgreSQLContainer<>("postgres:16-alpine")) {
            pg.start();
            Flyway.configure().dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
                    .locations("classpath:db/migration").target(BEFORE).load().migrate();
            try (Connection sql = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())) {
                long issued = Instant.parse("2026-10-12T01:00:00Z").getEpochSecond();
                long expires = Instant.parse("2026-10-15T01:00:00Z").getEpochSecond();
                UUID invitedUser = user(sql, "invited@example.test", "{portal-invitation}" + issued + ":" + expires + ":" + DIGEST);
                UUID oldUser = user(sql, "old@example.test", "{bcrypt}$2a$10$legacy");
                UUID activeUser = user(sql, "active@example.test", "{bcrypt}$2a$10$active");
                UUID invitedCustomer = customer(sql, invitedUser, "invited@example.test", "INACTIVE");
                UUID closedCustomer = customer(sql, oldUser, "old@example.test", "INACTIVE");
                UUID unlinkedCustomer = customer(sql, null, "unlinked@example.test", "INACTIVE");
                UUID activeCustomer = customer(sql, activeUser, "active@example.test", "ACTIVE");
                // Blocked before this rule existed: a CUSTOMER account with a live session, and a staff
                // account that happens to be linked to a blocked profile.
                UUID blockedUser = user(sql, "blocked@example.test", "{bcrypt}$2a$10$blocked");
                role(sql, blockedUser, "CUSTOMER");
                customer(sql, blockedUser, "blocked@example.test", "BLOCKED");
                UUID liveSession = session(sql, blockedUser);
                role(sql, oldUser, "CUSTOMER");
                UUID linkedStaff = user(sql, "linked-staff@example.test", "{bcrypt}$2a$10$staff");
                role(sql, linkedStaff, "SALES_STAFF");
                customer(sql, linkedStaff, "linked-staff@example.test", "BLOCKED");
                UUID staffSession = session(sql, linkedStaff);
                long salesVersion = roleVersion(sql, "SALES_STAFF");
                long customerRoleVersion = roleVersion(sql, "CUSTOMER");

                // Up to this migration only: later ones (V20261015000200/0300, production grants and quotes)
                // move some of the same role versions again, and this test is about what 000100 does.
                var migrated = Flyway.configure().dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
                        .locations("classpath:db/migration").target("20261015000100").load();
                assertThat(migrated.migrate().migrations).extracting(m -> m.version).contains("20261015000100");
                migrated.validate();

                assertThat(text(sql, "select status from customer.customer where id=?", invitedCustomer))
                        .isEqualTo("PENDING_ACTIVATION");
                for (UUID untouched : new UUID[]{closedCustomer, unlinkedCustomer}) {
                    assertThat(text(sql, "select status from customer.customer where id=?", untouched))
                            .isEqualTo("INACTIVE");
                }
                assertThat(text(sql, "select status from customer.customer where id=?", activeCustomer))
                        .isEqualTo("ACTIVE");
                assertThat(number(sql, "select count(*) from customer.customer where tax_code is not null")).isZero();

                // Suspended and closed customers' CUSTOMER accounts no longer sign in; staff keep theirs.
                assertThat(text(sql, "select status from identity.app_user where id=?", blockedUser))
                        .isEqualTo("DISABLED");
                assertThat(text(sql, "select status from identity.app_user where id=?", oldUser))
                        .isEqualTo("DISABLED");
                assertThat(text(sql, "select revoked_reason from identity.user_session where id=?", liveSession))
                        .isEqualTo("ACCOUNT_DISABLED");
                assertThat(text(sql, "select status from identity.app_user where id=?", linkedStaff))
                        .isEqualTo("ACTIVE");
                assertThat(text(sql, "select coalesce(revoked_reason,'live') from identity.user_session where id=?",
                        staffSession)).isEqualTo("live");
                assertThat(text(sql, "select status from identity.app_user where id=?", activeUser))
                        .isEqualTo("ACTIVE");

                assertThat(text(sql, "select coalesce(password_hash,'<null>') from identity.app_user where id=?", invitedUser))
                        .isEqualTo("<null>");
                assertThat(text(sql, "select password_hash from identity.app_user where id=?", oldUser))
                        .isEqualTo("{bcrypt}$2a$10$legacy");
                assertThat(text(sql, """
                        select token_digest || '|' || extract(epoch from issued_at)::bigint || '|'
                               || extract(epoch from expires_at)::bigint || '|' || coalesce(delivery_status, '-')
                        from identity.portal_invitation where user_id=? and consumed_at is null and revoked_at is null""",
                        invitedUser))
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

    /** Every rule V20261015000100 states, refused or accepted by the database itself. */
    private static void assertConstraints(Connection sql, UUID invitedUser, UUID activeUser) throws SQLException {
        update(sql, "update customer.customer set tax_code='0312345678' where email='active@example.test'");
        refused(sql, "a second ACTIVE customer with the tax code",
                "insert into customer.customer(id,full_name,email,status,tax_code,version,created_at) values"
                        + " (gen_random_uuid(),'Dup','dup@example.test','ACTIVE','0312345678',0,now())");
        refused(sql, "a PENDING customer with a live tax code",
                "insert into customer.customer(id,user_id,full_name,email,status,tax_code,version,created_at) values"
                        + " (gen_random_uuid(),?,'Dup','p2@example.test','PENDING_ACTIVATION','0312345678',0,now())",
                user(sql, "p2@example.test", null));
        update(sql, "insert into customer.customer(id,full_name,email,status,tax_code,version,created_at) values"
                + " (gen_random_uuid(),'Closed twin','closed-twin@example.test','INACTIVE','0312345678',0,now())");
        update(sql, "insert into customer.customer(id,full_name,email,status,tax_code,version,created_at) values"
                + " (gen_random_uuid(),'Blocked twin','blocked-twin@example.test','BLOCKED','0312345678',0,now())");
        refused(sql, "a malformed tax code",
                "update customer.customer set tax_code='031234567' where email='active@example.test'");
        refused(sql, "an unnormalised dependent-unit code",
                "update customer.customer set tax_code='0312345678001' where email='active@example.test'");
        update(sql, "update customer.customer set tax_code='0312345678-001' where email='active@example.test'");
        refused(sql, "a pending customer without an account",
                "insert into customer.customer(id,full_name,email,status,version,created_at) values"
                        + " (gen_random_uuid(),'No account','none@example.test','PENDING_ACTIVATION',0,now())");
        refused(sql, "an unknown status",
                "update customer.customer set status='SUSPENDED' where email='active@example.test'");

        String open = "insert into identity.portal_invitation(id,user_id,token_digest,issued_at,expires_at) values"
                + " (gen_random_uuid(),?,?,now(),now()+interval '1 day')";
        refused(sql, "a second open invitation for one account", open, invitedUser, "f".repeat(64));
        refused(sql, "a raw token instead of a digest", open, activeUser, "Z".repeat(64));
        refused(sql, "an invitation that expires before it is issued",
                "insert into identity.portal_invitation(id,user_id,token_digest,issued_at,expires_at) values"
                        + " (gen_random_uuid(),?,?,now(),now())", activeUser, "e".repeat(64));
        refused(sql, "consumed and revoked at once",
                "update identity.portal_invitation set consumed_at=issued_at+interval '1 minute',"
                        + " revoked_at=issued_at+interval '1 minute' where user_id=?", invitedUser);
        refused(sql, "consumed after it expired",
                "update identity.portal_invitation set consumed_at=expires_at where user_id=?", invitedUser);
        refused(sql, "a delivery outcome without its time",
                "update identity.portal_invitation set delivery_status='SENT' where user_id=?", invitedUser);
        update(sql, "update identity.portal_invitation set revoked_at=issued_at+interval '1 minute' where user_id=?",
                invitedUser);
        update(sql, open, invitedUser, "f".repeat(64));
        refused(sql, "a reused token digest",
                "insert into identity.portal_invitation(id,user_id,token_digest,issued_at,expires_at,revoked_at) values"
                        + " (gen_random_uuid(),?,?,now(),now()+interval '1 day',now())", activeUser, "f".repeat(64));
    }

    private static UUID user(Connection sql, String email, String passwordHash) throws SQLException {
        UUID id = UUID.randomUUID();
        update(sql, "insert into identity.app_user(id,username,email,password_hash,status,version,created_at)"
                + " values (?,?,?,?,'ACTIVE',0,now())", id, email, email, passwordHash);
        return id;
    }

    private static void role(Connection sql, UUID userId, String code) throws SQLException {
        update(sql, "insert into identity.user_role(id,user_id,role_id,version,created_at)"
                + " select gen_random_uuid(),?,id,0,now() from identity.app_role where code=?", userId, code);
    }

    private static UUID session(Connection sql, UUID userId) throws SQLException {
        UUID id = UUID.randomUUID();
        update(sql, "insert into identity.user_session(id,user_id,issued_at,expires_at,version,created_at)"
                + " values (?,?,now(),now()+interval '8 hours',0,now())", id, userId);
        return id;
    }

    private static UUID customer(Connection sql, UUID userId, String email, String status) throws SQLException {
        UUID id = UUID.randomUUID();
        update(sql, "insert into customer.customer(id,user_id,full_name,email,status,version,created_at)"
                + " values (?,?,'Co',?,?,0,now())", id, userId, email, status);
        return id;
    }

    private static long roleVersion(Connection sql, String code) throws SQLException {
        return number(sql, "select version from identity.app_role where code=?", code);
    }
}
