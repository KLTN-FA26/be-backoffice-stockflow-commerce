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
 * V20261116000100 on the real upgrade path (team plan 4B-11): the money ledger and the per-order payment terms are
 * dropped. QD-1: nothing is archived and no stop condition guards the drop, so rows in every ledger table, a legacy order
 * and the publications of removed listeners are simply cleaned up. What must survive: orders as history (a legacy
 * CONFIRMED order without a contract stays, a cancelled one keeps its fee and what it retained), the permissions the
 * code still uses; the roles' versions advance only for roles that lost a grant.
 *
 * <p>Rows are written with triggers and foreign keys off ({@code session_replication_role = replica}): what is tested is
 * the migration, not the flows that make the rows.</p>
 */
class MoneyLedgerRemovalMigrationUpgradeTest {

    private static final String BEFORE = "20261115000300";   // the migration right before it on the merged branch (final rescope merge), so other lines' role version bumps run first
    private static final String MIGRATION = "20261116000100";
    private static final String CUSTOMER = "00000000-0000-4000-8000-0000000000c1";
    private static final String ACCOUNTANT = "00000000-0000-4000-8000-0000000000a1";
    private static final String LEGACY = "00000000-0000-4000-8000-0000000000d1";
    private static final String CANCELLED = "00000000-0000-4000-8000-0000000000d2";

    @Test
    void theLedgerGoesAndTheHistoryStays() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            flyway(postgres, BEFORE).migrate();
            try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())) {
                seed(db);
                long accountantBefore = version(db, "ACCOUNTANT");
                long warehouseBefore = version(db, "WAREHOUSE_STAFF");

                var result = flyway(postgres, MIGRATION).migrate();
                // Merged with the other lines, migrations of theirs may sit between BEFORE and this one (final rescope merge).
                assertThat(result.migrations).extracting(m -> m.version).last().isEqualTo(MIGRATION);

                assertThat(RawSql.texts(db, "SELECT table_name FROM information_schema.tables WHERE table_schema = "
                        + "'payment' ORDER BY 1")).as("what is left of payment")
                        .doesNotContain("refund", "receivable", "payment", "customer_transfer", "transfer_allocation",
                                "cod_remittance", "cod_remittance_line")
                        .contains("installment", "order_settlement", "payment_receipt", "refund_record");
                assertThat(RawSql.number(db, "SELECT count(*) FROM information_schema.tables WHERE table_schema = "
                        + "'ordering' AND table_name = 'order_payment'")).isZero();
                assertThat(RawSql.number(db, "SELECT count(*) FROM pg_proc WHERE proname = 'credit_refund_outcome'"))
                        .isZero();
                assertThat(RawSql.texts(db, "SELECT table_name || '.' || column_name FROM information_schema.columns "
                        + "WHERE (table_schema, table_name) IN (('ordering', 'customer_order'), ('ordering', 'quote'), "
                        + "('ordering', 'order_shipment'), ('customer', 'credit_profile')) AND column_name IN "
                        + "('payment_term', 'paid_amount', 'paid_in_full_at', 'deposit_required', 'balance_policy', "
                        + "'billed_amount', 'remainder_fee_amount', 'allow_credit', 'allow_prepaid', 'allow_deposit', "
                        + "'default_payment_term', 'deposit_percent')")).as("the dropped columns").isEmpty();
                assertThat(RawSql.texts(db, "SELECT column_name FROM information_schema.columns WHERE table_schema = "
                        + "'customer' AND table_name = 'credit_profile' AND column_name IN ('credit_limit', "
                        + "'credit_term_days') ORDER BY 1")).as("credit stays a limit and its days")
                        .containsExactly("credit_limit", "credit_term_days");
                assertThat(RawSql.number(db, "SELECT count(*) FROM pg_constraint WHERE conname = "
                        + "'ck_return_request_credit_transfer'")).as("a credit note books no transfer any more")
                        .isZero();

                assertThat(RawSql.texts(db, "SELECT order_number || ':' || status || ':' || "
                        + "coalesce(cancellation_retained_amount::text, '-') FROM ordering.customer_order ORDER BY 1"))
                        .as("the legacy CONFIRMED order is kept without a contract, the retained amount too")
                        .containsExactly("SO-MLR-CANCELLED:CANCELLED:200.0000", "SO-MLR-LEGACY:CONFIRMED:-");
                RawSql.refused(db, "ck_order_confirmed_basis", "INSERT INTO ordering.customer_order (id, order_number, "
                        + "customer_id, request_id, status, total_amount, currency, placed_at, version, created_at) "
                        + "VALUES (gen_random_uuid(), 'SO-MLR-NEW', '" + CUSTOMER + "', gen_random_uuid(), "
                        + "'CONFIRMED', 1, 'VND', NOW(), 0, NOW())");
                assertThat(RawSql.update(db, "INSERT INTO ordering.customer_order (id, order_number, customer_id, "
                        + "request_id, status, total_amount, currency, placed_at, version, created_at, "
                        + "contract_signed_at) VALUES (gen_random_uuid(), 'SO-MLR-SIGNED', '" + CUSTOMER
                        + "', gen_random_uuid(), 'CONFIRMED', 1, 'VND', NOW(), 0, NOW(), NOW())"))
                        .as("a CONFIRMED order of a signed contract").isEqualTo(1);
                RawSql.refused(db, "ck_order_cancellation_fee", "UPDATE ordering.customer_order SET "
                        + "cancellation_fee_amount = 0 WHERE id = '" + CANCELLED + "'");

                assertThat(RawSql.texts(db, "SELECT listener_id FROM event_publication"))
                        .as("only the unrelated publication is left").singleElement().asString()
                        .contains("OrderNotificationListener");
                assertThat(RawSql.number(db, "SELECT count(*) FROM identity.permission WHERE resource IN "
                        + "('payment-customer-transfers', 'payment-cod-remittances', 'payment-payments', "
                        + "'payment-settlements', 'payment-receivables', 'payment-order-payments')")).isZero();
                assertThat(RawSql.number(db, "SELECT count(*) FROM identity.role_permission rp LEFT JOIN "
                        + "identity.permission p ON p.id = rp.permission_id WHERE p.id IS NULL"))
                        .as("no grant left without its permission").isZero();
                assertThat(RawSql.number(db, "SELECT count(*) FROM identity.permission WHERE resource = "
                        + "'payment-refunds'")).as("refund records are 4B-9's: kept").isPositive();
                assertThat(version(db, "ACCOUNTANT")).as("the accountant lost grants: its version moves")
                        .isGreaterThan(accountantBefore);
                assertThat(version(db, "WAREHOUSE_STAFF")).as("a role that held none keeps its version")
                        .isEqualTo(warehouseBefore);
            }
        }
    }

    private static long version(Connection db, String role) throws SQLException {
        return RawSql.number(db, "SELECT version FROM identity.app_role WHERE code = ?", role);
    }

    private static void seed(Connection db) throws SQLException {
        RawSql.update(db, "SET session_replication_role = replica");
        RawSql.update(db, "INSERT INTO identity.app_user (id, username, email, password_hash, status, version, "
                + "created_at) VALUES ('" + ACCOUNTANT + "', 'mlr-acc', 'mlr@example.com', '!', 'ACTIVE', 0, NOW())");
        RawSql.update(db, "INSERT INTO customer.customer (id, full_name, email, status, version, created_at) VALUES "
                + "('" + CUSTOMER + "', 'Mlr', 'mlr1@example.com', 'ACTIVE', 0, NOW())");
        // Every shape of the old terms on one profile: deposit, balance policy, credit with its limit and days.
        RawSql.update(db, "INSERT INTO customer.credit_profile (id, customer_id, allow_prepaid, allow_deposit, "
                + "deposit_percent, deposit_balance_policy, allow_credit, default_payment_term, credit_limit, "
                + "credit_term_days, approved_by, approved_at) VALUES (gen_random_uuid(), '" + CUSTOMER
                + "', true, true, 30, 'BEFORE_DELIVERY', true, 'PREPAID', 5000000, 30, '" + ACCOUNTANT + "', NOW())");
        // A legacy order CONFIRMED as a prepaid order paid in full, with no contract.
        RawSql.update(db, "INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, "
                + "total_amount, currency, placed_at, created_at, payment_term, paid_amount, paid_in_full_at) VALUES "
                + "('" + LEGACY + "', 'SO-MLR-LEGACY', '" + CUSTOMER + "', gen_random_uuid(), 'CONFIRMED', 1000, "
                + "'VND', NOW(), NOW(), 'PREPAID', 1000, NOW())");
        // A cancelled order that kept a fee and retained 200 of what was paid.
        RawSql.update(db, "INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, "
                + "total_amount, currency, placed_at, created_at, payment_term, paid_amount, paid_in_full_at, "
                + "cancellation_reason_code, cancellation_reason, cancellation_fee_reason, cancellation_fee_detail, "
                + "cancellation_fee_amount, cancellation_retained_amount, printing_started_at) VALUES ('" + CANCELLED
                + "', 'SO-MLR-CANCELLED', '" + CUSTOMER + "', gen_random_uuid(), 'CANCELLED', 1000, 'VND', NOW(), "
                + "NOW(), 'PREPAID', 1000, NOW(), 'OTHER', 'x', 'Printing done', '4 cups', 200, 200, NOW())");
        RawSql.update(db, "INSERT INTO ordering.quote (id, quote_number, request_id, customer_id, status, revision, "
                + "payment_term, deposit_percent, valid_until, version, created_at) VALUES "
                + "('00000000-0000-4000-8000-0000000000e1', 'QT-MLR', gen_random_uuid(), '" + CUSTOMER
                + "', 'DRAFT', 1, 'DEPOSIT', 30, NOW() + interval '7 days', 0, NOW())");
        // One row in each ledger table.
        RawSql.update(db, "INSERT INTO payment.customer_transfer (id, customer_id, reference, amount, "
                + "unallocated_amount, currency, received_on, recorded_by, recorded_at) VALUES "
                + "('00000000-0000-4000-8000-0000000000f1', '" + CUSTOMER + "', 'FT-MLR', 1000, 1000, 'VND', "
                + "CURRENT_DATE, '" + ACCOUNTANT + "', NOW())");
        RawSql.update(db, "INSERT INTO payment.payment (id, order_id, amount, method, status, created_at) VALUES "
                + "(gen_random_uuid(), '" + LEGACY + "', 1000, 'BANK_TRANSFER', 'CAPTURED', NOW())");
        RawSql.update(db, "INSERT INTO ordering.order_payment (payment_id, order_id, amount, currency, captured_at) "
                + "VALUES (gen_random_uuid(), '" + LEGACY + "', 1000, 'VND', NOW())");
        RawSql.update(db, "INSERT INTO payment.receivable (id, order_id, customer_id, amount, currency, issued_at, "
                + "due_date) VALUES (gen_random_uuid(), '" + LEGACY + "', '" + CUSTOMER + "', 500, 'VND', NOW(), "
                + "CURRENT_DATE)");
        // Publications of the listeners that went with the ledger, and an unrelated one.
        RawSql.update(db, "INSERT INTO event_publication (id, listener_id, event_type, serialized_event, "
                + "publication_date) VALUES (gen_random_uuid(), 'com.stockflow.order.internal.service."
                + "PaymentEventListener.on(com.stockflow.contracts.PaymentCaptured)', "
                + "'com.stockflow.contracts.PaymentCaptured', '{}', NOW())");
        RawSql.update(db, "INSERT INTO event_publication (id, listener_id, event_type, serialized_event, "
                + "publication_date) VALUES (gen_random_uuid(), 'com.stockflow.payment.internal.service."
                + "OrderCancelledListener.on(com.stockflow.contracts.OrderCancelled)', "
                + "'com.stockflow.contracts.OrderCancelled', '{}', NOW())");
        RawSql.update(db, "INSERT INTO event_publication (id, listener_id, event_type, serialized_event, "
                + "publication_date) VALUES (gen_random_uuid(), 'com.stockflow.notification.internal.service."
                + "OrderNotificationListener.on(com.stockflow.contracts.OrderPlaced)', "
                + "'com.stockflow.contracts.OrderPlaced', '{}', NOW())");
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
