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
 * V20261108003200 on the real upgrade path (team plan 4B-10): the code stops mapping the per-order payment terms and
 * the customer's old terms, so the migration rewrites {@code ck_credit_profile_credit} (credit is a limit and the days
 * to pay, no longer tied to {@code allow_credit}) and {@code ck_order_confirmed_basis} (a free replacement order is
 * confirmed as itself, not as a prepaid order paid in full), and gives {@code quote.payment_term} a default.
 *
 * <p>Rows the old rules accepted - credit profiles of every shape the old CHECK let in, orders CONFIRMED on each old
 * basis - must pass the rewritten CHECKs as they are (the migration validates them when it adds them), and the rows the
 * code writes from now on, with the unmapped columns at their defaults, must be accepted. Rows are written with
 * triggers off ({@code session_replication_role = replica}): what is tested is the CHECKs, not the flows that make the
 * rows.</p>
 */
class PaymentTermsRemovalMigrationUpgradeTest {

    private static final String BEFORE = "20261108003000";
    private static final String MIGRATION = "20261108003200";
    private static final String CUSTOMER = "00000000-0000-4000-8000-0000000000c1";
    private static final String NO_TERMS = "00000000-0000-4000-8000-0000000000c2";
    private static final String LIMIT_ONLY = "00000000-0000-4000-8000-0000000000c3";
    private static final String RETURN_REQUEST = "00000000-0000-4000-8000-0000000000c4";
    private static final String ACCOUNTANT = "00000000-0000-4000-8000-0000000000c6";

    @Test
    void oldTermsStayValidAndNewRowsNeedNone() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            flyway(postgres, BEFORE).migrate();
            try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())) {
                seed(db);

                var result = flyway(postgres, MIGRATION).migrate();
                // Merged with the other lines, migrations of theirs may sit between BEFORE and this one (final rescope merge).
                assertThat(result.migrations).extracting(m -> m.version).last().isEqualTo(MIGRATION);

                assertThat(RawSql.number(db, "SELECT count(*) FROM customer.credit_profile"))
                        .as("every credit profile kept").isEqualTo(3);
                assertThat(RawSql.texts(db, "SELECT order_number || ':' || status || ':' || payment_term "
                        + "FROM ordering.customer_order ORDER BY order_number")).as("every order kept as it was")
                        .containsExactly("SO-UPG-CREDIT:CONFIRMED:CREDIT", "SO-UPG-DEPOSIT:CONFIRMED:DEPOSIT",
                                "SO-UPG-PREPAID:CONFIRMED:PREPAID", "SO-UPG-REPLACE:CONFIRMED:PREPAID");
                assertThat(RawSql.number(db, "SELECT count(*) FROM pg_constraint WHERE conrelid IN "
                        + "('customer.credit_profile'::regclass, 'ordering.customer_order'::regclass) "
                        + "AND convalidated = false")).as("every CHECK validated").isZero();

                // Credit from now on: a limit and days to pay, whatever allow_credit says; one without the other never.
                RawSql.update(db, "INSERT INTO customer.customer (id, full_name, email, status, version, created_at) "
                        + "VALUES ('00000000-0000-4000-8000-0000000000c5', 'Moi', 'moi@example.com', 'ACTIVE', 0, "
                        + "NOW())");
                assertThat(RawSql.update(db, "INSERT INTO customer.credit_profile (id, customer_id, credit_limit, "
                        + "credit_term_days, approved_by, approved_at) VALUES (gen_random_uuid(), "
                        + "'00000000-0000-4000-8000-0000000000c5', 50000000, 30, ?::uuid, NOW())", ACCOUNTANT))
                        .isEqualTo(1);
                RawSql.refused(db, "ck_credit_profile_credit", "UPDATE customer.credit_profile SET "
                        + "credit_term_days = NULL WHERE customer_id = '00000000-0000-4000-8000-0000000000c5'");
                RawSql.refused(db, "ck_credit_profile_credit", "UPDATE customer.credit_profile SET "
                        + "credit_limit = -1 WHERE customer_id = '00000000-0000-4000-8000-0000000000c5'");

                // An order from now on: no term written, the default; CONFIRMED only on a contract or as a replacement.
                RawSql.update(db, "INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, "
                        + "status, total_amount, currency, placed_at, created_at) VALUES (gen_random_uuid(), "
                        + "'SO-UPG-NEW', ?::uuid, gen_random_uuid(), 'DRAFT', 1000, 'VND', NOW(), NOW())", CUSTOMER);
                assertThat(RawSql.text(db, "SELECT payment_term FROM ordering.customer_order "
                        + "WHERE order_number = 'SO-UPG-NEW'")).isEqualTo("PREPAID");
                RawSql.refused(db, "ck_order_confirmed_basis", "UPDATE ordering.customer_order SET status = "
                        + "'CONFIRMED' WHERE order_number = 'SO-UPG-NEW'");

                // A quote from now on: no term written.
                RawSql.update(db, "SET session_replication_role = replica");
                RawSql.update(db, "INSERT INTO ordering.quote (id, quote_number, request_id, customer_id, status, "
                        + "valid_until) VALUES (gen_random_uuid(), 'QT-UPG-1', gen_random_uuid(), ?::uuid, 'DRAFT', "
                        + "NOW() + INTERVAL '7 days')", CUSTOMER);
                RawSql.update(db, "SET session_replication_role = origin");
                assertThat(RawSql.text(db, "SELECT payment_term FROM ordering.quote WHERE quote_number = 'QT-UPG-1'"))
                        .isEqualTo("PREPAID");
            }
        }
    }

    private static void seed(Connection db) throws SQLException {
        RawSql.update(db, "SET session_replication_role = replica");
        RawSql.update(db, "INSERT INTO identity.app_user (id, username, email, password_hash, status, version, "
                + "created_at) VALUES (?::uuid, 'upg-accountant', 'upg@example.com', '!', 'ACTIVE', 0, NOW())",
                ACCOUNTANT);
        for (String customer : new String[] {CUSTOMER, NO_TERMS, LIMIT_ONLY}) {
            RawSql.update(db, "INSERT INTO customer.customer (id, full_name, email, status, version, created_at) "
                    + "VALUES (?::uuid, 'Upgrade', ?, 'ACTIVE', 0, NOW())", customer, customer + "@example.com");
        }
        // Credit allowed: a limit and its days.
        RawSql.update(db, "INSERT INTO customer.credit_profile (id, customer_id, allow_prepaid, allow_deposit, "
                + "allow_credit, default_payment_term, credit_limit, credit_term_days, approved_by, approved_at) VALUES "
                + "(gen_random_uuid(), ?::uuid, true, false, true, 'CREDIT', 100000000, 30, ?::uuid, NOW())", CUSTOMER,
                ACCOUNTANT);
        // No credit: neither.
        RawSql.update(db, "INSERT INTO customer.credit_profile (id, customer_id, allow_prepaid, allow_deposit, "
                + "allow_credit, default_payment_term, approved_by, approved_at) VALUES (gen_random_uuid(), ?::uuid, true, "
                + "false, false, 'PREPAID', ?::uuid, NOW())", NO_TERMS, ACCOUNTANT);
        // Credit not allowed, a limit kept without days to pay: the old CHECK let it in (allow_credit was false and
        // the pair not both set).
        RawSql.update(db, "INSERT INTO customer.credit_profile (id, customer_id, allow_prepaid, allow_deposit, "
                + "allow_credit, default_payment_term, credit_limit, approved_by, approved_at) VALUES (gen_random_uuid(), "
                + "?::uuid, true, false, false, 'PREPAID', 5000000, ?::uuid, NOW())", LIMIT_ONLY, ACCOUNTANT);

        String order = "INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, "
                + "total_amount, currency, placed_at, created_at, payment_term, %s) VALUES (gen_random_uuid(), ?, "
                + "?::uuid, gen_random_uuid(), 'CONFIRMED', 1000, 'VND', NOW(), NOW(), ?, %s)";
        RawSql.update(db, order.formatted("paid_amount, paid_in_full_at", "1000, NOW()"), "SO-UPG-PREPAID", CUSTOMER,
                "PREPAID");
        RawSql.update(db, order.formatted("deposit_required, deposit_received_at, balance_policy",
                "300, NOW(), 'BEFORE_DELIVERY'"), "SO-UPG-DEPOSIT",
                CUSTOMER, "DEPOSIT");
        RawSql.update(db, order.formatted("credit_term_days", "30"), "SO-UPG-CREDIT", CUSTOMER, "CREDIT");
        // A free replacement of a return, confirmed as the old code did: prepaid, paid in full.
        RawSql.update(db, "INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, "
                + "total_amount, currency, placed_at, created_at, payment_term, paid_in_full_at, "
                + "replaces_return_request_id) VALUES (gen_random_uuid(), 'SO-UPG-REPLACE', ?::uuid, "
                + "gen_random_uuid(), 'CONFIRMED', 0, 'VND', NOW(), NOW(), 'PREPAID', NOW(), ?::uuid)", CUSTOMER,
                RETURN_REQUEST);
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
