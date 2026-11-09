package com.stockflow;

import com.stockflow.support.RawSql;
import java.sql.Connection;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V20261108002000 on the real upgrade path (kltn-docs 14 BR-04, BR-07, BR-08; 01 BR-10; 13 BR-02, BR-05; team plan
 * 4B-2, QD-16): a database holding a quote line, an order line and a customer price written before - on a blank, its
 * design and units - is upgraded. They are kept as they were. A new quote or order line is cartons of the customer's own
 * SKU in its carton unit at the unit's factor, one line per SKU; the unit keeps its factor once a line counts in it; a
 * new customer price names the customer's own SKU.
 */
class QuoteLinesByCartonMigrationUpgradeTest {

    private static final String BEFORE = "20261108001900";
    private static final String MIGRATION = "20261108002000";
    private static final String CUSTOMER = "00000000-0000-4000-8000-0000000000c2";
    private static final String OTHER = "00000000-0000-4000-8000-0000000000c3";
    private static final String PRODUCT = "00000000-0000-4000-8000-0000000000e1";
    private static final String BLANK_VARIANT = "00000000-0000-4000-8000-0000000000e2";
    private static final String OWN_VARIANT = "00000000-0000-4000-8000-0000000000e3";
    private static final String DRAFT = "00000000-0000-4000-8000-0000000000e4";
    private static final String SNAPSHOT = "00000000-0000-4000-8000-0000000000e5";
    private static final String OLD_QUOTE = "00000000-0000-4000-8000-0000000000f1";
    private static final String NEW_QUOTE = "00000000-0000-4000-8000-0000000000f2";
    private static final String ORDER = "00000000-0000-4000-8000-0000000000f3";
    private static final String CARTON = "00000000-0000-4000-8000-0000000000a1";
    private static final String BLANK = "UPG-BLANK";
    private static final String OWN = "UPG-BLANK-C1";

    @Test
    void oldLinesAreKeptNewLinesAreCartonsOfTheCustomersOwnSku() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            flyway(postgres, BEFORE).migrate();
            try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())) {
                for (String customer : new String[] {CUSTOMER, OTHER}) {
                    RawSql.update(db, "INSERT INTO customer.customer (id, full_name, email, status, version, created_at) "
                            + "VALUES (?::uuid, 'Upgrade', ?, 'ACTIVE', 0, NOW())", customer, customer + "@example.com");
                }
                RawSql.update(db, "INSERT INTO product.products (id, code, name, slug, status, kind, version, created_at) "
                        + "VALUES (?::uuid, 'UPG', 'Ly giay', 'upg', 'DRAFT', 'CUSTOMIZABLE', 0, NOW())", PRODUCT);
                RawSql.update(db, "INSERT INTO product.variants (id, product_id, sku, name, status, is_default, "
                        + "attribute_signature, position, version, created_at) VALUES (?::uuid, ?::uuid, ?, 'Ly', "
                        + "'ACTIVE', TRUE, 'DEFAULT', 0, 0, NOW())", BLANK_VARIANT, PRODUCT, BLANK);
                RawSql.update(db, "INSERT INTO design.design_draft (id, customer_id, product_id, status, created_at, "
                        + "current_artifacts) VALUES (?::uuid, ?::uuid, ?::uuid, 'DRAFT', NOW(), '{}'::jsonb)", DRAFT,
                        CUSTOMER, PRODUCT);
                RawSql.update(db, "INSERT INTO design.design_snapshot (id, draft_id, checksum, artifact_url, "
                        + "confirmed_at, created_at, artifact_manifest) VALUES (?::uuid, ?::uuid, ?, 'https://x/p.pdf', "
                        + "NOW(), NOW(), '[]'::jsonb)", SNAPSHOT, DRAFT, "d".repeat(64));
                RawSql.update(db, "INSERT INTO product.variants (id, product_id, sku, name, status, is_default, "
                        + "attribute_signature, position, version, created_at, owner_customer_id, design_snapshot_id, "
                        + "source_variant_id) VALUES (?::uuid, ?::uuid, ?, 'Ly in logo', 'ACTIVE', FALSE, ?, 1, 0, NOW(), "
                        + "?::uuid, ?::uuid, ?::uuid)", OWN_VARIANT, PRODUCT, OWN, "OWNED-SKU:" + OWN, CUSTOMER,
                        SNAPSHOT, BLANK_VARIANT);
                RawSql.update(db, "INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor, "
                        + "is_base, default_purchase, active, version, created_at, default_shipping) SELECT ?::uuid, i.id, "
                        + "'CARTON', 50, FALSE, FALSE, TRUE, 0, NOW(), TRUE FROM inventory.inventory_items i "
                        + "WHERE i.sku = ?", CARTON, OWN);
                // Written before: a quote line on the blank and its design, an order line, a price on the blank.
                RawSql.update(db, "INSERT INTO ordering.quote (id, quote_number, request_id, customer_id, status, "
                        + "revision, payment_term, valid_until, version, created_at) VALUES (?::uuid, 'QT-UPG-1', "
                        + "gen_random_uuid(), ?::uuid, 'DRAFT', 1, 'PREPAID', NOW() + interval '7 days', 0, NOW())",
                        OLD_QUOTE, CUSTOMER);
                RawSql.update(db, "INSERT INTO ordering.quote_line (id, quote_id, line_no, blank_sku, design_snapshot_id, "
                        + "print_spec, quantity) VALUES (gen_random_uuid(), ?::uuid, 1, ?, ?::uuid, 'Logo', 600)",
                        OLD_QUOTE, BLANK, SNAPSHOT);
                RawSql.update(db, "INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, "
                        + "status, total_amount, currency, placed_at, created_at) VALUES (?::uuid, 'SO-UPG-1', ?::uuid, "
                        + "gen_random_uuid(), 'DRAFT', 1000000, 'VND', NOW(), NOW())", ORDER, CUSTOMER);
                RawSql.update(db, "INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency) "
                        + "VALUES (gen_random_uuid(), ?::uuid, ?, 500, 2000, 'VND')", ORDER, BLANK);
                RawSql.update(db, "INSERT INTO catalog.customer_price (id, customer_id, sku, version, created_at) "
                        + "VALUES (gen_random_uuid(), ?::uuid, ?, 0, NOW())", CUSTOMER, BLANK);

                var result = flyway(postgres, MIGRATION).migrate();
                assertThat(result.migrations).extracting(m -> m.version).containsExactly(MIGRATION);

                // Kept as they were (QD-16).
                assertThat(RawSql.text(db, "SELECT blank_sku || '/' || quantity || '/' || (sku IS NULL) FROM "
                        + "ordering.quote_line WHERE quote_id = ?::uuid", OLD_QUOTE)).isEqualTo(BLANK + "/600/true");
                assertThat(RawSql.number(db, "SELECT count(*) FROM ordering.order_line WHERE cartons IS NULL "
                        + "AND sku = ?", BLANK)).isEqualTo(1);
                assertThat(RawSql.number(db, "SELECT count(*) FROM catalog.customer_price WHERE sku = ?", BLANK))
                        .isEqualTo(1);

                // A carton line of the customer's own SKU, at the carton unit's factor.
                RawSql.update(db, "INSERT INTO ordering.quote (id, quote_number, request_id, customer_id, status, "
                        + "revision, payment_term, valid_until, version, created_at) VALUES (?::uuid, 'QT-UPG-2', "
                        + "gen_random_uuid(), ?::uuid, 'DRAFT', 1, 'PREPAID', NOW() + interval '7 days', 0, NOW())",
                        NEW_QUOTE, CUSTOMER);
                RawSql.update(db, cartonLine(1, OWN, CARTON, 20, 50));
                RawSql.refused(db, "uk_quote_line_customer_sku: one line per SKU", cartonLine(2, OWN, CARTON, 5, 50));
                RawSql.refused(db, "the factor fixed on the line is the unit's", cartonLine(3, OWN, CARTON, 5, 40));
                RawSql.refused(db, "not the SKU's unit", cartonLine(4, BLANK, CARTON, 5, 50));
                RawSql.refused(db, "ck_quote_line_cartons: at most 10,000,000 pieces",
                        cartonLine(5, OWN, CARTON, 200_001, 50));
                RawSql.refused(db, "ck_quote_line_shape: never a mix", "INSERT INTO ordering.quote_line (id, quote_id, "
                        + "line_no, sku, uom_conversion_id, cartons, units_per_carton, blank_sku, design_snapshot_id, "
                        + "quantity) VALUES (gen_random_uuid(), '" + NEW_QUOTE + "', 6, '" + OWN + "', '" + CARTON
                        + "', 5, 50, '" + BLANK + "', '" + SNAPSHOT + "', 250)");
                RawSql.refused(db, "ck_quote_line_accepted: cartons with a price", "UPDATE ordering.quote_line SET "
                        + "accepted_cartons = 20 WHERE quote_id = '" + NEW_QUOTE + "'");
                RawSql.update(db, "UPDATE ordering.quote_line SET accepted_cartons = 20, accepted_unit_price = 1800 "
                        + "WHERE quote_id = ?::uuid", NEW_QUOTE);

                // An order line in cartons: its pieces are cartons x pieces per carton; it may carry the date.
                RawSql.refused(db, "ck_order_line_cartons", orderLine(999, 20));
                RawSql.update(db, orderLine(1000, 20));
                assertThat(RawSql.number(db, "SELECT count(*) FROM ordering.order_line WHERE cartons = 20 AND "
                        + "requested_delivery_date IS NOT NULL")).isEqualTo(1);

                // The unit now counts quote and order lines: its factor is kept (01 BR-10).
                assertThat(RawSql.text(db, "SELECT inventory.unit_in_use(?::uuid)::text", CARTON)).isEqualTo("true");
                RawSql.refused(db, "a unit in use keeps its factor", "UPDATE inventory.uom_conversions SET factor = 25 "
                        + "WHERE id = '" + CARTON + "'");
                assertThat(RawSql.text(db, "SELECT inventory.item_in_use(i.id, i.sku)::text FROM "
                        + "inventory.inventory_items i WHERE i.sku = ?", OWN)).isEqualTo("true");

                // A new customer price names the customer's own SKU; not a blank, not another customer's SKU.
                RawSql.refused(db, "tg_customer_price_own_sku: a blank", "INSERT INTO catalog.customer_price (id, "
                        + "customer_id, sku, version, created_at) VALUES (gen_random_uuid(), '" + OTHER + "', '" + BLANK
                        + "', 0, NOW())");
                RawSql.refused(db, "tg_customer_price_own_sku: another customer's SKU", "INSERT INTO "
                        + "catalog.customer_price (id, customer_id, sku, version, created_at) VALUES (gen_random_uuid(), '"
                        + OTHER + "', '" + OWN + "', 0, NOW())");
                RawSql.update(db, "INSERT INTO catalog.customer_price (id, customer_id, sku, version, created_at) "
                        + "VALUES (gen_random_uuid(), ?::uuid, ?, 0, NOW())", CUSTOMER, OWN);
            }
        }
    }

    private static String cartonLine(int lineNo, String sku, String unit, int cartons, int perCarton) {
        return "INSERT INTO ordering.quote_line (id, quote_id, line_no, sku, uom_conversion_id, cartons, "
                + "units_per_carton) VALUES (gen_random_uuid(), '" + NEW_QUOTE + "', " + lineNo + ", '" + sku + "', '"
                + unit + "', " + cartons + ", " + perCarton + ")";
    }

    private static String orderLine(int pieces, int cartons) {
        return "INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency, uom_conversion_id, "
                + "cartons, units_per_carton, requested_delivery_date) VALUES (gen_random_uuid(), '" + ORDER + "', '"
                + OWN + "', " + pieces + ", 1800, 'VND', '" + CARTON + "', " + cartons + ", 50, CURRENT_DATE + 30)";
    }

    private static Flyway flyway(PostgreSQLContainer<?> postgres, String target) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }
}
