package com.stockflow;

import com.stockflow.support.RawSql;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * V20261108001900 on the real upgrade path (kltn-docs 18 BR-03, BR-04, 14 BR-13, 15 BR-12; team plan 4B-1): existing
 * customers get no schedule; a schedule's shares add up to 100 when its transaction commits; an installment after
 * delivery needs the customer's approved credit - a limit above 0 - and that credit cannot be lowered to 0, taken away
 * or deleted under it; an approval is only on a schedule paying after delivery; what the application writes (the
 * installments deleted and written again, the customer deleted with it) is accepted; Sales and accounting get the
 * permission, their versions moving (ADR-0008).
 */
class DefaultPaymentScheduleMigrationUpgradeTest {

    private static final String BEFORE = "20261108001800";
    private static final String MIGRATION = "20261108001900";
    private static final String USER = "00000000-0000-4000-8000-0000000000aa";
    private static final String CUSTOMER = "00000000-0000-4000-8000-0000000000c1";
    private static final String SCHEDULE = "00000000-0000-4000-8000-0000000000d1";

    @Test
    void upgradeChecksTheScheduleAtCommit() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            flyway(postgres, BEFORE).migrate();
            try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())) {
                RawSql.update(db, "INSERT INTO identity.app_user (id, username, email, password_hash, status, version, "
                        + "created_at) VALUES (?::uuid, 'qa', 'qa@example.com', '!x', 'ACTIVE', 0, NOW())", USER);
                RawSql.update(db, "INSERT INTO customer.customer (id, full_name, email, status, version, created_at) "
                        + "VALUES (?::uuid, 'QA', 'qa-c@example.com', 'ACTIVE', 0, NOW())", CUSTOMER);
                RawSql.update(db, "INSERT INTO customer.credit_profile (id, customer_id, allow_prepaid, allow_deposit, "
                        + "allow_credit, default_payment_term, credit_limit, credit_term_days, approved_by, approved_at) "
                        + "VALUES (gen_random_uuid(), ?::uuid, true, false, false, 'PREPAID', NULL, NULL, ?::uuid, NOW())",
                        CUSTOMER, USER);
                long sales = version(db, "SALES_STAFF");
                long accountant = version(db, "ACCOUNTANT");
                long warehouse = version(db, "WAREHOUSE_STAFF");

                var result = flyway(postgres, MIGRATION).migrate();
                assertThat(result.migrations).extracting(m -> m.version).containsExactly(MIGRATION);
                assertThat(RawSql.number(db, "SELECT count(*) FROM customer.default_payment_schedule")).isZero();

                // Shares adding up to 90: refused when the transaction commits.
                assertRefusedAtCommit(db, "100 rule", () -> {
                    schedule(db, null);
                    line(db, 1, "60", "ON_SIGNING");
                    line(db, 2, "30", "BEFORE_DELIVERY");
                });
                // An approval on a schedule that pays nothing after delivery: refused.
                assertRefusedAtCommit(db, "nothing to approve", () -> {
                    schedule(db, USER);
                    line(db, 1, "100", "ON_SIGNING");
                });
                // After delivery without credit - and then with credit whose limit is 0: refused, approved or not.
                assertRefusedAtCommit(db, "no credit", () -> {
                    schedule(db, USER);
                    line(db, 1, "40", "ON_SIGNING");
                    line(db, 2, "60", "AFTER_DELIVERY");
                });
                RawSql.update(db, "UPDATE customer.credit_profile SET allow_credit = true, credit_limit = 0, "
                        + "credit_term_days = 30 WHERE customer_id = ?::uuid", CUSTOMER);
                assertRefusedAtCommit(db, "a limit of 0", () -> {
                    schedule(db, null);
                    line(db, 1, "40", "ON_SIGNING");
                    line(db, 2, "60", "AFTER_DELIVERY");
                });

                // With credit approved: Sales' proposal is kept, awaiting approval.
                RawSql.update(db, "UPDATE customer.credit_profile SET credit_limit = 50000000 WHERE customer_id = "
                        + "?::uuid", CUSTOMER);
                inOneTransaction(db, () -> {
                    schedule(db, null);
                    line(db, 1, "40", "ON_SIGNING");
                    line(db, 2, "60", "AFTER_DELIVERY");
                });
                assertThat(RawSql.number(db, "SELECT count(*) FROM customer.default_payment_installment")).isEqualTo(2);
                // The installments rewritten whole in one transaction, as Hibernate replaces a collection: accepted.
                inOneTransaction(db, () -> {
                    RawSql.update(db, "DELETE FROM customer.default_payment_installment WHERE schedule_id = ?::uuid",
                            SCHEDULE);
                    line(db, 1, "25", "ON_SIGNING");
                    line(db, 2, "25", "BEFORE_DELIVERY");
                    line(db, 3, "50", "AFTER_DELIVERY");
                });
                RawSql.update(db, "UPDATE customer.default_payment_schedule SET approved_by = ?::uuid, "
                        + "approved_at = NOW() WHERE id = ?::uuid", USER, SCHEDULE);

                // The credit cannot be taken away, lowered to 0 or deleted under it.
                assertRefusedAtCommit(db, "credit taken away", () -> RawSql.update(db, "UPDATE customer.credit_profile "
                        + "SET allow_credit = false, credit_limit = NULL, credit_term_days = NULL WHERE customer_id = "
                        + "?::uuid", CUSTOMER));
                assertRefusedAtCommit(db, "limit lowered to 0", () -> RawSql.update(db, "UPDATE "
                        + "customer.credit_profile SET credit_limit = 0 WHERE customer_id = ?::uuid", CUSTOMER));
                assertRefusedAtCommit(db, "credit deleted", () -> RawSql.update(db, "DELETE FROM "
                        + "customer.credit_profile WHERE customer_id = ?::uuid", CUSTOMER));
                assertRefusedAtCommit(db, "an installment removed", () -> RawSql.update(db, "DELETE FROM "
                        + "customer.default_payment_installment WHERE seq = 3"));
                RawSql.refused(db, "ck_default_payment_installment_percent", "UPDATE customer.default_payment_installment "
                        + "SET percent = 0 WHERE seq = 1");
                RawSql.refused(db, "ck_default_payment_installment_milestone", "UPDATE "
                        + "customer.default_payment_installment SET milestone = 'ON_DELIVERY' WHERE seq = 1");

                // The customer deleted with its schedule: nothing left to check.
                RawSql.update(db, "DELETE FROM customer.customer WHERE id = ?::uuid", CUSTOMER);
                assertThat(RawSql.number(db, "SELECT count(*) FROM customer.default_payment_schedule")).isZero();

                assertThat(RawSql.texts(db, "SELECT r.code || ':' || p.action FROM identity.role_permission rp "
                        + "JOIN identity.app_role r ON r.id = rp.role_id JOIN identity.permission p "
                        + "ON p.id = rp.permission_id WHERE p.resource = 'customer-payment-schedules' AND r.code IN "
                        + "('SALES_STAFF', 'ACCOUNTANT') ORDER BY 1"))
                        .containsExactly("ACCOUNTANT:APPROVE", "ACCOUNTANT:READ", "ACCOUNTANT:UPDATE",
                                "SALES_STAFF:READ", "SALES_STAFF:UPDATE");
                assertThat(version(db, "SALES_STAFF")).isEqualTo(sales + 1);
                assertThat(version(db, "ACCOUNTANT")).isEqualTo(accountant + 1);
                assertThat(version(db, "WAREHOUSE_STAFF")).isEqualTo(warehouse);
            }
        }
    }

    private interface Statements {
        void run() throws SQLException;
    }

    private static void inOneTransaction(Connection db, Statements statements) throws SQLException {
        db.setAutoCommit(false);
        try {
            statements.run();
            db.commit();
        } catch (SQLException e) {
            db.rollback();
            throw e;
        } finally {
            db.setAutoCommit(true);
        }
    }

    /** The statements, in one transaction, are refused when it commits (SQLSTATE class 23). */
    private static void assertRefusedAtCommit(Connection db, String rule, Statements statements) throws SQLException {
        db.setAutoCommit(false);
        SQLException refusal = catchThrowableOfType(SQLException.class, () -> {
            statements.run();
            db.commit();
        });
        db.rollback();
        db.setAutoCommit(true);
        if (refusal == null) {
            throw new AssertionError(rule + " is refused at commit, but the database accepted it");
        }
        assertThat(refusal.getSQLState()).as(rule + ": " + refusal.getMessage()).startsWith("23");
    }

    private static void schedule(Connection db, String approvedBy) throws SQLException {
        RawSql.update(db, "INSERT INTO customer.default_payment_schedule (id, customer_id, set_by, set_at, approved_by, "
                + "approved_at) VALUES (?::uuid, ?::uuid, ?::uuid, NOW(), ?::uuid, CASE WHEN ?::uuid IS NULL THEN NULL "
                + "ELSE NOW() END)", SCHEDULE, CUSTOMER, USER, approvedBy, approvedBy);
    }

    private static void line(Connection db, int seq, String percent, String milestone) throws SQLException {
        RawSql.update(db, "INSERT INTO customer.default_payment_installment (schedule_id, seq, name, percent, milestone) "
                + "VALUES (?::uuid, ?, 'Đợt', ?::numeric, ?)", SCHEDULE, seq, percent, milestone);
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
