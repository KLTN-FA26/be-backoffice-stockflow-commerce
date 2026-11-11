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
 * V20261108002100 on the real upgrade path (kltn-docs 14 BR-09, BR-17, BR-20, BR-21; QD-9; team plan 4B-3): an order
 * placed before keeps its total as its total before tax with no VAT; the VAT rates are seeded; fee lines are one shipping
 * fee and one plate fee per SKU, whole VND, the plate fee's SKU on the quote (checked at commit); an order's total before
 * tax is generated from its total and its VAT; the product rollups count cartons from 0.
 */
class VatAndFeeLinesMigrationUpgradeTest {

    private static final String BEFORE = "20261108002000";
    private static final String MIGRATION = "20261108002100";
    private static final String CUSTOMER = "00000000-0000-4000-8000-0000000000c4";
    private static final String PRODUCT = "00000000-0000-4000-8000-0000000000e6";
    private static final String ORDER = "00000000-0000-4000-8000-0000000000f7";
    private static final String QUOTE = "00000000-0000-4000-8000-0000000000f8";
    private static final String SKU = "UPG-VAT";
    private static final String OWN = "UPG-VAT-OWN";
    private static final String USER = "00000000-0000-4000-8000-0000000000fb";
    private static final String DRAFT_QUOTE = "00000000-0000-4000-8000-0000000000f9";
    private static final String SENT_QUOTE = "00000000-0000-4000-8000-0000000000fa";

    @Test
    void oldOrdersCarryNoVatNewFeeLinesAreChecked() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            flyway(postgres, BEFORE).migrate();
            try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())) {
                RawSql.update(db, "INSERT INTO customer.customer (id, full_name, email, status, version, created_at) "
                        + "VALUES (?::uuid, 'Upgrade', 'vat@example.com', 'ACTIVE', 0, NOW())", CUSTOMER);
                RawSql.update(db, "INSERT INTO product.products (id, code, name, slug, status, kind, tax_class, version, "
                        + "created_at) VALUES (?::uuid, 'UPGV', 'Ly', 'upgv', 'DRAFT', 'CUSTOMIZABLE', 'REDUCED', 0, NOW())",
                        PRODUCT);
                RawSql.update(db, "INSERT INTO product.variants (id, product_id, sku, name, status, is_default, "
                        + "attribute_signature, position, version, created_at) VALUES (gen_random_uuid(), ?::uuid, ?, "
                        + "'Ly', 'ACTIVE', TRUE, 'DEFAULT', 0, 0, NOW())", PRODUCT, SKU);
                // A customer's own SKU in cartons of 50, quoted before 4B-3 on a draft and on a quote already sent.
                RawSql.update(db, "INSERT INTO identity.app_user (id, username, email, password_hash, status, version, "
                        + "created_at) VALUES (?::uuid, 'vat-sales', 'vat-sales@example.com', '!x', 'ACTIVE', 0, NOW())",
                        USER);
                RawSql.update(db, "INSERT INTO design.design_draft (id, customer_id, product_id, status, created_at, "
                        + "current_artifacts) SELECT gen_random_uuid(), ?::uuid, ?::uuid, 'DRAFT', NOW(), '{}'::jsonb",
                        CUSTOMER, PRODUCT);
                RawSql.update(db, "INSERT INTO design.design_snapshot (id, draft_id, checksum, artifact_url, "
                        + "confirmed_at, created_at, artifact_manifest) SELECT gen_random_uuid(), d.id, ?, "
                        + "'https://x/p.pdf', NOW(), NOW(), '[]'::jsonb FROM design.design_draft d", "e".repeat(64));
                RawSql.update(db, "INSERT INTO product.variants (id, product_id, sku, name, status, is_default, "
                        + "attribute_signature, position, version, created_at, owner_customer_id, design_snapshot_id, "
                        + "source_variant_id) SELECT gen_random_uuid(), ?::uuid, ?, 'Ly in logo', 'ACTIVE', FALSE, ?, 1, 0, "
                        + "NOW(), ?::uuid, s.id, v.id FROM design.design_snapshot s, product.variants v WHERE v.sku = ?",
                        PRODUCT, OWN, "OWNED-SKU:" + OWN, CUSTOMER, SKU);
                RawSql.update(db, "INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, "
                        + "factor, is_base, default_purchase, active, version, created_at, default_shipping) SELECT "
                        + "gen_random_uuid(), i.id, 'CARTON', 50, FALSE, FALSE, TRUE, 0, NOW(), TRUE "
                        + "FROM inventory.inventory_items i WHERE i.sku = ?", OWN);
                for (String[] quote : new String[][] {{DRAFT_QUOTE, "QT-UPG-VAT-D", "DRAFT"},
                        {SENT_QUOTE, "QT-UPG-VAT-S", "SENT"}}) {
                    RawSql.update(db, "INSERT INTO ordering.quote (id, quote_number, request_id, customer_id, status, "
                            + "revision, payment_term, valid_until, sent_at, sent_by, version, created_at) VALUES "
                            + "(?::uuid, ?, gen_random_uuid(), ?::uuid, ?, 1, 'PREPAID', NOW() + interval '7 days', "
                            + "CASE WHEN ? = 'SENT' THEN NOW() END, CASE WHEN ? = 'SENT' THEN ?::uuid END, 0, NOW())",
                            quote[0], quote[1], CUSTOMER, quote[2], quote[2], quote[2], USER);
                    RawSql.update(db, "INSERT INTO ordering.quote_line (id, quote_id, line_no, sku, uom_conversion_id, "
                            + "cartons, units_per_carton) SELECT gen_random_uuid(), ?::uuid, 1, ?, u.id, 20, 50 "
                            + "FROM inventory.uom_conversions u JOIN inventory.inventory_items i "
                            + "ON i.id = u.inventory_item_id WHERE i.sku = ? AND u.unit_of_measure = 'CARTON'", quote[0],
                            OWN, OWN);
                }
                RawSql.update(db, "INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, "
                        + "status, total_amount, currency, placed_at, created_at) VALUES (?::uuid, 'SO-UPG-VAT', ?::uuid, "
                        + "gen_random_uuid(), 'DRAFT', 1250000, 'VND', NOW(), NOW())", ORDER, CUSTOMER);
                RawSql.update(db, "INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency) "
                        + "VALUES (gen_random_uuid(), ?::uuid, ?, 1, 1250000, 'VND')", ORDER, SKU);
                RawSql.update(db, "INSERT INTO reporting.product_sales_summary (id, sku, period, quantity_sold, "
                        + "revenue, version, created_at) VALUES (gen_random_uuid(), ?, '2026-10-24', 5, 100, 0, NOW())", SKU);

                var result = flyway(postgres, MIGRATION).migrate();
                assertThat(result.migrations).extracting(m -> m.version).containsExactly(MIGRATION);

                // An order placed before: its total is its total before tax, its VAT 0.
                assertThat(RawSql.text(db, "SELECT net_amount || '/' || tax_amount FROM ordering.customer_order "
                        + "WHERE id = ?::uuid", ORDER)).isEqualTo("1250000.0000/0.0000");
                assertThat(RawSql.number(db, "SELECT count(*) FROM ordering.order_line WHERE tax_rate IS NULL"))
                        .isEqualTo(1);
                assertThat(RawSql.number(db, "SELECT cartons_sold FROM reporting.product_sales_summary WHERE sku = ?",
                        SKU)).isZero();
                refusedBy(db, "ck_product_sales_summary_cartons", "UPDATE reporting.product_sales_summary SET "
                        + "cartons_sold = -1");
                // A draft's carton line takes its product's VAT, as a line drafted from now on does; a quote already
                // sent keeps the offer it made, without VAT (14 BR-06).
                assertThat(RawSql.text(db, "SELECT tax_class || ':' || tax_rate FROM ordering.quote_line WHERE quote_id "
                        + "= ?::uuid", DRAFT_QUOTE)).isEqualTo("REDUCED:5.00");
                assertThat(RawSql.number(db, "SELECT count(*) FROM ordering.quote_line WHERE quote_id = ?::uuid "
                        + "AND tax_class IS NULL AND tax_rate IS NULL", SENT_QUOTE)).isEqualTo(1);
                // QD-9: the VAT rates.
                assertThat(RawSql.texts(db, "SELECT tax_class || ':' || rate FROM ordering.tax_rate ORDER BY 1"))
                        .containsExactly("EXEMPT:0.00", "REDUCED:5.00", "STANDARD:10.00");
                refusedBy(db, "ck_tax_rate_class", "INSERT INTO ordering.tax_rate (tax_class, rate) VALUES ('LUXURY', 20)");

                // The three totals add up: the total before tax is generated from the other two.
                refusedBy(db, "ck_order_totals", "UPDATE ordering.customer_order SET tax_amount = 1250001 WHERE id = '"
                        + ORDER + "'");
                refusedBy(db, "ck_order_totals", "UPDATE ordering.customer_order SET tax_amount = 0.5 WHERE id = '"
                        + ORDER + "'");
                RawSql.update(db, "UPDATE ordering.customer_order SET tax_amount = 125000, total_amount = 1375000 "
                        + "WHERE id = ?::uuid", ORDER);
                assertThat(RawSql.text(db, "SELECT net_amount FROM ordering.customer_order WHERE id = ?::uuid", ORDER))
                        .isEqualTo("1250000.0000");
                SQLException written = catchThrowableOfType(SQLException.class, () -> RawSql.update(db,
                        "UPDATE ordering.customer_order SET net_amount = 1 WHERE id = ?::uuid", ORDER));
                if (written == null) {
                    throw new AssertionError("the total before tax is the database's to compute");
                }
                assertThat(written.getMessage()).contains("generated column");
                refusedBy(db, "ck_order_line_tax", "UPDATE ordering.order_line SET tax_rate = 10 WHERE order_id = '"
                        + ORDER + "'");
                refusedBy(db, "ck_order_line_tax", "UPDATE ordering.order_line SET tax_rate = 10, tax_amount = 125001 "
                        + "WHERE order_id = '" + ORDER + "'");
                RawSql.update(db, "UPDATE ordering.order_line SET tax_rate = 10, tax_amount = 125000 WHERE order_id = "
                        + "?::uuid", ORDER);

                // Fee lines of an order: one shipping fee, more than zero; a plate fee names its SKU; whole VND.
                RawSql.update(db, orderFee("SHIPPING_FEE", null, "300000"));
                refusedBy(db, "ck_order_fee_line_tax", "UPDATE ordering.order_fee_line SET tax_amount = 0 WHERE order_id = '"
                        + ORDER + "'");
                refusedBy(db, "uk_order_fee_line_shipping", orderFee("SHIPPING_FEE", null, "100000"));
                refusedBy(db, "ck_order_fee_line_amount", orderFee("PLATE_FEE", SKU, "100.5"));
                refusedBy(db, "ck_order_fee_line_sku", orderFee("PLATE_FEE", null, "100"));
                RawSql.update(db, orderFee("PLATE_FEE", SKU, "0"));
                refusedBy(db, "uk_order_fee_line_plate", orderFee("PLATE_FEE", SKU, "1"));

                // A quote's plate fee is for a SKU among its carton lines: a line drafted before names a blank.
                RawSql.update(db, "INSERT INTO ordering.quote (id, quote_number, request_id, customer_id, status, "
                        + "revision, payment_term, valid_until, version, created_at) VALUES (?::uuid, 'QT-UPG-VAT', "
                        + "gen_random_uuid(), ?::uuid, 'DRAFT', 1, 'PREPAID', NOW() + interval '7 days', 0, NOW())",
                        QUOTE, CUSTOMER);
                refusedBy(db, "ck_quote_fee_line_amount", quoteFee("SHIPPING_FEE", null, "0"));
                refusedBy(db, "ck_quote_fee_line_amount", quoteFee("SHIPPING_FEE", null, null));
                db.setAutoCommit(false);
                try {
                    RawSql.update(db, quoteFee("PLATE_FEE", SKU, null));
                    SQLException stray = catchThrowableOfType(SQLException.class, db::commit);
                    if (stray == null) {
                        throw new AssertionError("a plate fee for a SKU not on the quote is refused at commit");
                    }
                    assertThat(stray.getMessage()).contains("a plate fee is for a SKU on the quote");
                } finally {
                    db.rollback();
                    db.setAutoCommit(true);
                }
            }
        }
    }

    private static String orderFee(String kind, String sku, String amount) {
        return "INSERT INTO ordering.order_fee_line (id, order_id, kind, sku, amount, tax_rate, tax_amount, currency) "
                + "VALUES (gen_random_uuid(), '" + ORDER + "', '" + kind + "', " + (sku == null ? "NULL" : "'" + sku + "'")
                + ", " + amount + ", 10, ROUND(" + amount + " * 0.10), 'VND')";
    }

    private static String quoteFee(String kind, String sku, String amount) {
        return "INSERT INTO ordering.quote_fee_line (id, quote_id, kind, sku, amount, tax_class, tax_rate) VALUES "
                + "(gen_random_uuid(), '" + QUOTE + "', '" + kind + "', " + (sku == null ? "NULL" : "'" + sku + "'")
                + ", " + (amount == null ? "NULL" : amount) + ", 'STANDARD', 10)";
    }

    /** Refused as an integrity violation by the rule {@code because} names. */
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
