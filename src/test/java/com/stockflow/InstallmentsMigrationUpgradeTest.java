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
 * V20261108002400 on the real upgrade path (kltn-docs 15 §4.1, §5.1, §5.3; team plan 4B-6): contracts signed before it
 * get the installments a signature makes now - the one on signing due from the signature, the others waiting for their
 * milestone, a cancelled order's cancelled - while a contract still drawn up gets none; the accountant and Sales are
 * granted the new resources and their roles' grant versions move.
 */
class InstallmentsMigrationUpgradeTest {

    private static final String BEFORE = "20261108002300";
    private static final String MIGRATION = "20261108002400";
    private static final String CUSTOMER = "00000000-0000-4000-8000-0000000000f1";
    private static final String USER = "00000000-0000-4000-8000-0000000000f2";
    private static final String PRODUCT = "00000000-0000-4000-8000-0000000000f3";
    private static final String SIGNED = "00000000-0000-4000-8000-00000000f100";
    private static final String CANCELLED = "00000000-0000-4000-8000-00000000f200";
    private static final String DRAFTED = "00000000-0000-4000-8000-00000000f300";
    private static final String BLANK = "UPG-IN";
    private static final String OWN = "UPG-IN-OWN";

    @Test
    void signedContractsGetTheirInstallments() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            flyway(postgres, BEFORE).migrate();
            try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())) {
                seed(db);
                long accountantVersion = version(db, "ACCOUNTANT");
                long warehouseVersion = version(db, "WAREHOUSE_STAFF");

                var result = flyway(postgres, MIGRATION).migrate();
                assertThat(result.migrations).extracting(m -> m.version).containsExactly(MIGRATION);

                // The signed contract: the one on signing due from the signature, by the day signed plus its days; the
                // one before delivery, tied to batch 2, waits for the release with no due day.
                assertThat(RawSql.texts(db, "SELECT seq || ':' || milestone || ':' || status || ':' "
                        + "|| COALESCE((due_date - CURRENT_DATE)::text, '-') || ':' || (reached_at IS NOT NULL) || ':' "
                        + "|| COALESCE(delivery_batch_seq::text, '-') || ':' || amount::bigint FROM payment.installment "
                        + "WHERE order_id = ?::uuid ORDER BY seq", SIGNED))
                        .containsExactly("1:ON_SIGNING:DUE:-1:true:-:1100000",
                                "2:BEFORE_DELIVERY:NOT_DUE:-:false:2:1100000");
                assertThat(RawSql.text(db, "SELECT contract_number || '/' || order_number FROM payment.installment "
                        + "WHERE order_id = ?::uuid AND seq = 1", SIGNED)).isEqualTo("HD-UPG-IN-1/SO-UPG-IN-1");
                // A cancelled order's contract stays SIGNED, its installments are owed no more.
                assertThat(RawSql.texts(db, "SELECT status || ':' || (cancelled_at IS NOT NULL) FROM payment.installment "
                        + "WHERE order_id = ?::uuid ORDER BY seq", CANCELLED))
                        .containsExactly("CANCELLED:true", "CANCELLED:true");
                // A contract still drawn up gets none: installments come with the signature.
                assertThat(RawSql.text(db, "SELECT count(*)::text FROM payment.installment WHERE order_id = ?::uuid",
                        DRAFTED)).isEqualTo("0");

                // What it is never changes (tg_installment_transition); only receipts pay it (check_installment_paid).
                refused(db, "what it is does not change", "UPDATE payment.installment SET amount = 1 "
                        + "WHERE order_id = '" + SIGNED + "' AND seq = 1");
                inOneTransaction(db, "its recorded receipts pay 0",
                        "UPDATE payment.installment SET paid_amount = 1100000, status = 'PAID', paid_at = NOW() "
                                + "WHERE order_id = '" + SIGNED + "' AND seq = 1");

                assertThat(RawSql.texts(db, "SELECT p.code FROM identity.role_permission rp JOIN identity.app_role r "
                        + "ON r.id = rp.role_id JOIN identity.permission p ON p.id = rp.permission_id "
                        + "WHERE r.code = 'ACCOUNTANT' AND p.resource IN ('payment-installments', 'payment-receipts') "
                        + "ORDER BY 1")).containsExactly("payment-installments:READ", "payment-installments:VIEW_PAGE",
                        "payment-receipts:CREATE", "payment-receipts:READ", "payment-receipts:UPDATE",
                        "payment-receipts:VIEW_PAGE");
                assertThat(version(db, "ACCOUNTANT")).as("grants are cached per role version (ADR-0008)")
                        .isEqualTo(accountantVersion + 1);
                assertThat(version(db, "WAREHOUSE_STAFF")).as("a role whose grants did not change keeps its version")
                        .isEqualTo(warehouseVersion);
            }
        }
    }

    private static long version(Connection db, String role) throws SQLException {
        return Long.parseLong(RawSql.text(db, "SELECT version::text FROM identity.app_role WHERE code = ?", role));
    }

    private static void seed(Connection db) throws SQLException {
        RawSql.update(db, "INSERT INTO identity.app_user (id, username, email, password_hash, status, version, "
                + "created_at) VALUES (?::uuid, 'in-sales', 'in-sales@example.com', '!x', 'ACTIVE', 0, NOW())", USER);
        RawSql.update(db, "INSERT INTO customer.customer (id, full_name, email, status, version, created_at) "
                + "VALUES (?::uuid, 'Cong ty IN', 'in@example.com', 'ACTIVE', 0, NOW())", CUSTOMER);
        RawSql.update(db, "INSERT INTO product.products (id, code, name, slug, status, kind, tax_class, version, "
                + "created_at) VALUES (?::uuid, 'UPGIN', 'Ly', 'upgin', 'DRAFT', 'CUSTOMIZABLE', 'STANDARD', 0, NOW())",
                PRODUCT);
        RawSql.update(db, "INSERT INTO product.variants (id, product_id, sku, name, status, is_default, "
                + "attribute_signature, position, version, created_at) VALUES (gen_random_uuid(), ?::uuid, ?, 'Ly', "
                + "'ACTIVE', TRUE, 'DEFAULT', 0, 0, NOW())", PRODUCT, BLANK);
        RawSql.update(db, "INSERT INTO design.design_draft (id, customer_id, product_id, status, created_at, "
                + "current_artifacts) SELECT gen_random_uuid(), ?::uuid, ?::uuid, 'DRAFT', NOW(), '{}'::jsonb",
                CUSTOMER, PRODUCT);
        RawSql.update(db, "INSERT INTO design.design_snapshot (id, draft_id, checksum, artifact_url, confirmed_at, "
                + "created_at, artifact_manifest) SELECT gen_random_uuid(), d.id, ?, 'https://x/p.pdf', NOW(), NOW(), "
                + "'[]'::jsonb FROM design.design_draft d", "8".repeat(64));
        RawSql.update(db, "INSERT INTO product.variants (id, product_id, sku, name, status, is_default, "
                + "attribute_signature, position, version, created_at, owner_customer_id, design_snapshot_id, "
                + "source_variant_id) SELECT gen_random_uuid(), ?::uuid, ?, 'Ly in logo', 'ACTIVE', FALSE, ?, 1, 0, "
                + "NOW(), ?::uuid, s.id, v.id FROM design.design_snapshot s, product.variants v WHERE v.sku = ?",
                PRODUCT, OWN, "OWNED-SKU:" + OWN, CUSTOMER, BLANK);
        RawSql.update(db, "INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor, "
                + "is_base, default_purchase, active, version, created_at, default_shipping) SELECT gen_random_uuid(), "
                + "i.id, 'CARTON', 50, FALSE, FALSE, TRUE, 0, NOW(), TRUE FROM inventory.inventory_items i "
                + "WHERE i.sku = ?", OWN);
        drawnUp(db, SIGNED, "1");
        drawnUp(db, CANCELLED, "2");
        drawnUp(db, DRAFTED, "3");
        sentAndSigned(db, SIGNED);
        sentAndSigned(db, CANCELLED);
        RawSql.update(db, "UPDATE ordering.customer_order SET status = 'CANCELLED', cancellation_reason = 'refused', "
                + "cancellation_reason_code = 'CREDIT_REJECTED' WHERE id = ?::uuid", CANCELLED);
    }

    /**
     * A draft order of 20 cartons (2,200,000 with tax) and its draft contract: two batches of 10 cartons, half on
     * signing (due 3 days after), half before batch 2 is handed over. Ids derive from the order's: {@code ...f1xx}.
     */
    private static void drawnUp(Connection db, String order, String n) throws SQLException {
        String prefix = order.substring(0, order.length() - 2);
        String line = prefix + "01";
        String contract = prefix + "02";
        String batch1 = prefix + "03";
        String batch2 = prefix + "04";
        RawSql.update(db, "INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, "
                + "total_amount, tax_amount, currency, placed_at, created_at) VALUES (?::uuid, ?, ?::uuid, "
                + "gen_random_uuid(), 'DRAFT', 2200000, 200000, 'VND', NOW(), NOW())", order, "SO-UPG-IN-" + n,
                CUSTOMER);
        RawSql.update(db, "INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency, "
                + "uom_conversion_id, cartons, units_per_carton, tax_rate, tax_amount) SELECT ?::uuid, ?::uuid, ?, "
                + "1000, 2000, 'VND', u.id, 20, 50, 10, 200000 FROM inventory.uom_conversions u JOIN "
                + "inventory.inventory_items i ON i.id = u.inventory_item_id WHERE i.sku = ? AND u.unit_of_measure = "
                + "'CARTON'", line, order, OWN, OWN);
        db.setAutoCommit(false);
        RawSql.update(db, "INSERT INTO ordering.sales_contract (id, contract_number, order_id, status, seller_name, "
                + "seller_tax_code, seller_address, seller_representative, seller_representative_title, buyer_name, "
                + "buyer_address, buyer_representative, buyer_representative_title, free_sample_rounds) VALUES "
                + "(?::uuid, ?, ?::uuid, 'DRAFT', 'Seller', '0101', 'A St', 'Ms A', 'Director', 'Cong ty IN', "
                + "'1 Le Loi', 'Mr B', 'Director', 2)", contract, "HD-UPG-IN-" + n, order);
        RawSql.update(db, "INSERT INTO ordering.delivery_batch (id, contract_id, seq, window_start, window_end) VALUES "
                + "(?::uuid, ?::uuid, 1, CURRENT_DATE + 30, CURRENT_DATE + 30), "
                + "(?::uuid, ?::uuid, 2, CURRENT_DATE + 45, CURRENT_DATE + 45)", batch1, contract, batch2, contract);
        RawSql.update(db, "INSERT INTO ordering.delivery_batch_line (id, delivery_batch_id, order_line_id, cartons) "
                + "VALUES (gen_random_uuid(), ?::uuid, ?::uuid, 10), (gen_random_uuid(), ?::uuid, ?::uuid, 10)",
                batch1, line, batch2, line);
        RawSql.update(db, "INSERT INTO ordering.contract_payment_installment (id, contract_id, seq, name, milestone, "
                + "percent, amount, due_days, delivery_batch_id) VALUES (gen_random_uuid(), ?::uuid, 1, 'Half', "
                + "'ON_SIGNING', 50, 1100000, 3, NULL), (gen_random_uuid(), ?::uuid, 2, 'Batch 2', 'BEFORE_DELIVERY', "
                + "50, 1100000, NULL, ?::uuid)", contract, contract, batch2);
        db.commit();
        db.setAutoCommit(true);
    }

    /** Sent, then signed four days ago: the order waits for its installment on signing. */
    private static void sentAndSigned(Connection db, String order) throws SQLException {
        String contract = order.substring(0, order.length() - 2) + "02";
        inOneTransaction(db, null, "UPDATE ordering.sales_contract SET status = 'SENT', sent_at = NOW() - INTERVAL "
                + "'5 days', sent_by = '" + USER + "', sent_document_key = 'documents/hd.pdf' WHERE id = '" + contract
                + "'", "UPDATE ordering.customer_order SET status = 'PENDING_CONTRACT' WHERE id = '" + order + "'");
        inOneTransaction(db, null, "UPDATE ordering.sales_contract SET status = 'SIGNED', signed_at = NOW(), "
                + "signed_by = '" + USER + "', signed_on = CURRENT_DATE - 4, signed_file_key = 'documents/s.pdf', "
                + "signed_file_name = 's.pdf' WHERE id = '" + contract + "'", "UPDATE ordering.customer_order SET "
                + "status = 'PENDING_PAYMENT', contract_signed_at = NOW() WHERE id = '" + order + "'");
    }

    /**
     * Runs the statements in one transaction. With {@code refusal} null it must commit; otherwise the commit must fail
     * with that message, and nothing is kept.
     */
    private static void inOneTransaction(Connection db, String refusal, String... statements) throws SQLException {
        db.setAutoCommit(false);
        try {
            for (String statement : statements) {
                RawSql.update(db, statement);
            }
            if (refusal == null) {
                db.commit();
                return;
            }
            SQLException refused = catchThrowableOfType(SQLException.class, db::commit);
            if (refused == null) {
                throw new AssertionError("refused at commit (" + refusal + "), but the database accepted it");
            }
            assertThat(refused.getMessage()).contains(refusal);
        } finally {
            db.rollback();
            db.setAutoCommit(true);
        }
    }

    private static void refused(Connection db, String because, String sql) {
        SQLException refusal = catchThrowableOfType(SQLException.class, () -> RawSql.update(db, sql));
        if (refusal == null) {
            throw new AssertionError("refused (" + because + "), but the database accepted: " + sql);
        }
        assertThat(refusal.getMessage()).contains(because);
    }

    private static Flyway flyway(PostgreSQLContainer<?> postgres, String target) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }
}
