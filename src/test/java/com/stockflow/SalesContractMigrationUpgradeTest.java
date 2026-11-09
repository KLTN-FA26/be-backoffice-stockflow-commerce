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
 * V20261108002200 on the real upgrade path (kltn-docs 14 §4.3, §5.2, BR-12, BR-13, BR-14, BR-23; 15 BR-12; 18 BR-05,
 * §6; team plan 4B-4): the buyer's legal profile and its change history; one live contract per order; the contract's
 * state machine and its content locked once sent; the schedule and plan rules checked at commit; the new grants, with
 * the holders' role versions advanced.
 */
class SalesContractMigrationUpgradeTest {

    private static final String BEFORE = "20261108002100";
    private static final String MIGRATION = "20261108002200";
    private static final String CUSTOMER = "00000000-0000-4000-8000-0000000000d1";
    private static final String USER = "00000000-0000-4000-8000-0000000000d2";
    private static final String PRODUCT = "00000000-0000-4000-8000-0000000000d3";
    private static final String ORDER = "00000000-0000-4000-8000-0000000000d4";
    private static final String LINE = "00000000-0000-4000-8000-0000000000d5";
    private static final String CONTRACT = "00000000-0000-4000-8000-0000000000d6";
    private static final String BLANK = "UPG-HD";
    private static final String OWN = "UPG-HD-OWN";

    @Test
    void contractsAreCheckedAtTheDatabase() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            flyway(postgres, BEFORE).migrate();
            try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())) {
                seed(db);
                long salesBefore = RawSql.number(db, "SELECT version FROM identity.app_role WHERE code = 'SALES_STAFF'");
                long warehouseBefore = RawSql.number(db,
                        "SELECT version FROM identity.app_role WHERE code = 'WAREHOUSE_STAFF'");

                var result = flyway(postgres, MIGRATION).migrate();
                assertThat(result.migrations).extracting(m -> m.version).containsExactly(MIGRATION);

                // Who works on contracts; only the roles granted something see their version move (ADR-0008).
                assertThat(RawSql.texts(db, "SELECT r.code || ':' || p.code FROM identity.role_permission rp "
                        + "JOIN identity.app_role r ON r.id = rp.role_id JOIN identity.permission p ON p.id = "
                        + "rp.permission_id WHERE p.resource = 'sales-contracts' AND r.code IN ('SALES_STAFF', "
                        + "'ACCOUNTANT', 'SALES_MANAGER') ORDER BY 1")).containsExactly(
                        "ACCOUNTANT:sales-contracts:READ", "SALES_MANAGER:sales-contracts:READ",
                        "SALES_STAFF:sales-contracts:APPROVE", "SALES_STAFF:sales-contracts:CREATE",
                        "SALES_STAFF:sales-contracts:READ", "SALES_STAFF:sales-contracts:UPDATE");
                assertThat(RawSql.number(db, "SELECT version FROM identity.app_role WHERE code = 'SALES_STAFF'"))
                        .isEqualTo(salesBefore + 1);
                assertThat(RawSql.number(db, "SELECT version FROM identity.app_role WHERE code = 'WAREHOUSE_STAFF'"))
                        .isEqualTo(warehouseBefore);

                // 18 BR-05, §6: the legal profile, one per customer, every field filled; its changes kept.
                refusedBy(db, "ck_customer_legal_profile_filled", legalProfile(" "));
                RawSql.update(db, legalProfile("Tran Van B"));
                refusedBy(db, "uk_customer_legal_profile_customer", legalProfile("Le Thi C"));
                RawSql.update(db, "INSERT INTO customer.customer_change_history (id, customer_id, field, old_value, "
                        + "new_value, changed_at) VALUES (gen_random_uuid(), ?::uuid, 'REGISTERED_ADDRESS', NULL, ?, "
                        + "NOW())", CUSTOMER, "x".repeat(500));
                refusedBy(db, "ck_customer_change_history_field", "INSERT INTO customer.customer_change_history (id, "
                        + "customer_id, field, new_value, changed_at) VALUES (gen_random_uuid(), '" + CUSTOMER
                        + "', 'BANK_ACCOUNT', 'x', NOW())");

                // A contract, its schedule and plan written together: accepted at commit.
                inOneTransaction(db, null, contract(CONTRACT, "HD-UPG-1"),
                        batch("b1", 1, "CURRENT_DATE + 30", "CURRENT_DATE + 32"), line("b1", 8),
                        batch("b2", 2, "CURRENT_DATE + 44", "CURRENT_DATE + 44"), line("b2", 12),
                        installment(1, "ON_SIGNING", 660000, "0", null),
                        installment(2, "BEFORE_DELIVERY", 1540000, "NULL", "b2"));
                // BR-12: one live contract per order.
                refusedBy(db, "uk_sales_contract_order_live", contract(id("c2"), "HD-UPG-2"));

                // The schedule and plan rules, at commit.
                inOneTransaction(db, "the cartons over the batches are not the order's",
                        "UPDATE ordering.delivery_batch_line SET cartons = 7 WHERE delivery_batch_id = '" + id("b1")
                                + "'");
                inOneTransaction(db, "add up to 2199999.0000, the order's total with tax is 2200000.0000",
                        "UPDATE ordering.contract_payment_installment SET amount = 659999 WHERE seq = 1");
                inOneTransaction(db, "starts before the one before it",
                        "UPDATE ordering.delivery_batch SET window_start = CURRENT_DATE + 29, window_end = "
                                + "CURRENT_DATE + 29 WHERE id = '" + id("b2") + "'");
                inOneTransaction(db, "carries no cartons",
                        "DELETE FROM ordering.delivery_batch_line WHERE delivery_batch_id = '" + id("b2") + "'",
                        "UPDATE ordering.delivery_batch_line SET cartons = 20 WHERE delivery_batch_id = '" + id("b1")
                                + "'");
                inOneTransaction(db, "needs credit accounting approved",
                        "UPDATE ordering.contract_payment_installment SET milestone = 'AFTER_DELIVERY', due_days = 30 "
                                + "WHERE seq = 2");
                refusedBy(db, "ck_contract_payment_installment_batch", "UPDATE ordering.contract_payment_installment "
                        + "SET delivery_batch_id = '" + id("b1") + "' WHERE seq = 1");
                refusedBy(db, "ck_contract_payment_installment_due", "UPDATE ordering.contract_payment_installment "
                        + "SET due_days = 3 WHERE seq = 2");
                refusedBy(db, "ck_contract_payment_installment_amount", "UPDATE ordering.contract_payment_installment "
                        + "SET amount = 0.5 WHERE seq = 1");
                refusedBy(db, "ck_delivery_batch_window", "UPDATE ordering.delivery_batch SET window_end = "
                        + "window_start - 1 WHERE id = '" + id("b1") + "'");

                // 14 §5.2: the state machine; BR-14: what a sent contract keeps.
                refusedBy(db, "is not a transition", "UPDATE ordering.sales_contract SET status = 'COMPLETED', "
                        + "completed_at = NOW() WHERE id = '" + CONTRACT + "'");
                refusedBy(db, "ck_sales_contract_sent", "UPDATE ordering.sales_contract SET status = 'SENT' WHERE id = '"
                        + CONTRACT + "'");
                RawSql.update(db, "UPDATE ordering.sales_contract SET status = 'SENT', sent_at = NOW(), sent_by = "
                        + "?::uuid, sent_document_key = 'documents/hd.pdf' WHERE id = ?::uuid", USER, CONTRACT);
                refusedBy(db, "does not change (14 BR-14)", "UPDATE ordering.sales_contract SET free_sample_rounds = 5 "
                        + "WHERE id = '" + CONTRACT + "'");
                refusedBy(db, "do not change (14 BR-14)", "UPDATE ordering.delivery_batch SET window_end = window_end "
                        + "+ 1 WHERE id = '" + id("b2") + "'");
                refusedBy(db, "do not change (14 BR-14)", "DELETE FROM ordering.contract_payment_installment WHERE "
                        + "seq = 2");
                refusedBy(db, "ck_sales_contract_signed", "UPDATE ordering.sales_contract SET status = 'SIGNED', "
                        + "signed_at = NOW(), signed_by = '" + USER + "', signed_on = CURRENT_DATE + 2, signed_file_key "
                        + "= 'documents/s.pdf', signed_file_name = 's.pdf' WHERE id = '" + CONTRACT + "'");
                RawSql.update(db, "UPDATE ordering.sales_contract SET status = 'SIGNED', signed_at = NOW(), signed_by = "
                        + "?::uuid, signed_on = CURRENT_DATE, signed_file_key = 'documents/s.pdf', signed_file_name = "
                        + "'s.pdf' WHERE id = ?::uuid", USER, CONTRACT);
                refusedBy(db, "is not a transition", "UPDATE ordering.sales_contract SET status = 'CANCELLED', "
                        + "cancelled_at = NOW(), cancelled_by = '" + USER + "', cancellation_reason = 'x', signed_at = "
                        + "NULL, signed_by = NULL, signed_on = NULL, signed_file_key = NULL, signed_file_name = NULL "
                        + "WHERE id = '" + CONTRACT + "'");
            }
        }
    }

    private static void seed(Connection db) throws SQLException {
        RawSql.update(db, "INSERT INTO identity.app_user (id, username, email, password_hash, status, version, "
                + "created_at) VALUES (?::uuid, 'hd-sales', 'hd-sales@example.com', '!x', 'ACTIVE', 0, NOW())", USER);
        RawSql.update(db, "INSERT INTO customer.customer (id, full_name, email, status, version, created_at) "
                + "VALUES (?::uuid, 'Cong ty HD', 'hd@example.com', 'ACTIVE', 0, NOW())", CUSTOMER);
        RawSql.update(db, "INSERT INTO product.products (id, code, name, slug, status, kind, tax_class, version, "
                + "created_at) VALUES (?::uuid, 'UPGHD', 'Ly', 'upghd', 'DRAFT', 'CUSTOMIZABLE', 'STANDARD', 0, NOW())",
                PRODUCT);
        RawSql.update(db, "INSERT INTO product.variants (id, product_id, sku, name, status, is_default, "
                + "attribute_signature, position, version, created_at) VALUES (gen_random_uuid(), ?::uuid, ?, 'Ly', "
                + "'ACTIVE', TRUE, 'DEFAULT', 0, 0, NOW())", PRODUCT, BLANK);
        RawSql.update(db, "INSERT INTO design.design_draft (id, customer_id, product_id, status, created_at, "
                + "current_artifacts) SELECT gen_random_uuid(), ?::uuid, ?::uuid, 'DRAFT', NOW(), '{}'::jsonb",
                CUSTOMER, PRODUCT);
        RawSql.update(db, "INSERT INTO design.design_snapshot (id, draft_id, checksum, artifact_url, confirmed_at, "
                + "created_at, artifact_manifest) SELECT gen_random_uuid(), d.id, ?, 'https://x/p.pdf', NOW(), NOW(), "
                + "'[]'::jsonb FROM design.design_draft d", "c".repeat(64));
        RawSql.update(db, "INSERT INTO product.variants (id, product_id, sku, name, status, is_default, "
                + "attribute_signature, position, version, created_at, owner_customer_id, design_snapshot_id, "
                + "source_variant_id) SELECT gen_random_uuid(), ?::uuid, ?, 'Ly in logo', 'ACTIVE', FALSE, ?, 1, 0, "
                + "NOW(), ?::uuid, s.id, v.id FROM design.design_snapshot s, product.variants v WHERE v.sku = ?",
                PRODUCT, OWN, "OWNED-SKU:" + OWN, CUSTOMER, BLANK);
        RawSql.update(db, "INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor, "
                + "is_base, default_purchase, active, version, created_at, default_shipping) SELECT gen_random_uuid(), "
                + "i.id, 'CARTON', 50, FALSE, FALSE, TRUE, 0, NOW(), TRUE FROM inventory.inventory_items i "
                + "WHERE i.sku = ?", OWN);
        // A draft order of 20 cartons of 50 at 2,000 a piece, 10% VAT: 2,200,000 with tax.
        RawSql.update(db, "INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, "
                + "total_amount, tax_amount, currency, placed_at, created_at) VALUES (?::uuid, 'SO-UPG-HD', ?::uuid, "
                + "gen_random_uuid(), 'DRAFT', 2200000, 200000, 'VND', NOW(), NOW())", ORDER, CUSTOMER);
        RawSql.update(db, "INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency, "
                + "uom_conversion_id, cartons, units_per_carton, tax_rate, tax_amount) SELECT ?::uuid, ?::uuid, ?, "
                + "1000, 2000, 'VND', u.id, 20, 50, 10, 200000 FROM inventory.uom_conversions u "
                + "JOIN inventory.inventory_items i ON i.id = u.inventory_item_id WHERE i.sku = ? "
                + "AND u.unit_of_measure = 'CARTON'", LINE, ORDER, OWN, OWN);
    }

    private static String legalProfile(String representative) {
        return "INSERT INTO customer.customer_legal_profile (id, customer_id, registered_address, representative_name, "
                + "representative_title) VALUES (gen_random_uuid(), '" + CUSTOMER + "', '1 Le Loi', '" + representative
                + "', 'Director')";
    }

    /** A stable id for a name, so a batch can be named by the rows that follow it. */
    private static String id(String name) {
        return java.util.UUID.nameUUIDFromBytes(("upg-hd:" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .toString();
    }

    private static String contract(String id, String number) {
        return "INSERT INTO ordering.sales_contract (id, contract_number, order_id, status, seller_name, seller_tax_code, "
                + "seller_address, seller_representative, seller_representative_title, buyer_name, buyer_address, "
                + "buyer_representative, buyer_representative_title, free_sample_rounds) VALUES ('" + id + "', '"
                + number + "', '" + ORDER + "', 'DRAFT', 'Seller', '0101', 'A St', 'Ms A', 'Director', 'Cong ty HD', "
                + "'1 Le Loi', 'Tran Van B', 'Director', 2)";
    }

    private static String batch(String name, int seq, String start, String end) {
        return "INSERT INTO ordering.delivery_batch (id, contract_id, seq, window_start, window_end) VALUES ('"
                + id(name) + "', '" + CONTRACT + "', " + seq + ", " + start + ", " + end + ")";
    }

    private static String line(String batch, int cartons) {
        return "INSERT INTO ordering.delivery_batch_line (id, delivery_batch_id, order_line_id, cartons) VALUES "
                + "(gen_random_uuid(), '" + id(batch) + "', '" + LINE + "', " + cartons + ")";
    }

    private static String installment(int seq, String milestone, long amount, String dueDays, String batch) {
        return "INSERT INTO ordering.contract_payment_installment (id, contract_id, seq, name, milestone, amount, "
                + "due_days, delivery_batch_id) VALUES (gen_random_uuid(), '" + CONTRACT + "', " + seq + ", 'Dot "
                + seq + "', '" + milestone + "', " + amount + ", " + dueDays + ", "
                + (batch == null ? "NULL" : "'" + id(batch) + "'") + ")";
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

    /** Refused by the rule {@code because} names - a constraint's name, or the message of a trigger. */
    private static void refusedBy(Connection db, String because, String sql) {
        SQLException refusal = catchThrowableOfType(SQLException.class, () -> RawSql.update(db, sql));
        if (refusal == null) {
            throw new AssertionError("refused by " + because + ", but the database accepted: " + sql);
        }
        assertThat(refusal.getSQLState()).as(refusal.getMessage()).startsWith("23");
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
