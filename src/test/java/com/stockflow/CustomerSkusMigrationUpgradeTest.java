package com.stockflow;

import com.stockflow.support.RawSql;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V20270105000100 on the real upgrade path (owner decisions Q4 and Q5 of 2026-11-26): a customer's SKU is a row of
 * {@code product.customer_skus}, and only a customer's SKU that an order named has an inventory item. Before it, the
 * customer's SKUs were rows of {@code product.variants} told apart by {@code owner_customer_id}, and every variant row -
 * the catalogue's blank included - had an item. After it:
 *
 * <ul>
 *   <li>each customer's SKU is a row of {@code customer_skus} with the id and code it had, drawn on its old source
 *       variant, and {@code variants} keeps the catalogue's rows only, without the three columns;</li>
 *   <li>the item of a SKU an order names stays as it was (same id, same variant); the item of a SKU nothing ordered and
 *       the blank's item go;</li>
 *   <li>a quote line, an order line, a customer price, a return request line and a chat context naming the SKU still
 *       name it, now through foreign keys to {@code customer_skus} (the chat's column renamed {@code customer_sku_id}).</li>
 * </ul>
 */
class CustomerSkusMigrationUpgradeTest {

    private static final String BEFORE = "20270104000200";
    private static final String CUSTOMER_SKUS = "20270105000100";

    private static final UUID PRODUCT = UUID.randomUUID();
    private static final UUID BLANK = UUID.randomUUID();
    private static final UUID ORDERED = UUID.randomUUID();
    private static final UUID UNORDERED = UUID.randomUUID();
    private static final UUID CUSTOMER = UUID.randomUUID();
    private static final UUID ORDERED_SNAPSHOT = UUID.randomUUID();
    private static final UUID UNORDERED_SNAPSHOT = UUID.randomUUID();
    private static final UUID ORDERED_ITEM = UUID.randomUUID();
    private static final UUID CARTON = UUID.randomUUID();
    private static final UUID CONTEXT = UUID.randomUUID();

    @Test
    void customerSkusMoveToTheirTableAndOnlyAnOrderedSkuKeepsItsItem() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            flyway(postgres, BEFORE).migrate();
            try (Connection db = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword())) {
                seed(db);

                var result = flyway(postgres, CUSTOMER_SKUS).migrate();
                assertThat(result.migrations).extracting(m -> m.version).containsExactly(CUSTOMER_SKUS);

                assertThat(RawSql.texts(db, "SELECT id || ':' || sku || ':' || variant_id || ':' || customer_id || ':' "
                        + "|| design_snapshot_id || ':' || status FROM product.customer_skus ORDER BY sku"))
                        .as("the customer's SKUs, with the ids and codes they had, on their old source variant")
                        .containsExactly(
                                UNORDERED + ":UPG-CUP-NEW:" + BLANK + ":" + CUSTOMER + ":" + UNORDERED_SNAPSHOT
                                        + ":ACTIVE",
                                ORDERED + ":UPG-CUP-ORD:" + BLANK + ":" + CUSTOMER + ":" + ORDERED_SNAPSHOT + ":ACTIVE");
                assertThat(RawSql.texts(db, "SELECT sku FROM product.variants")).as("the catalogue's rows only")
                        .containsExactly("UPG-CUP");
                assertThat(RawSql.number(db, "SELECT count(*) FROM information_schema.columns WHERE table_schema = "
                        + "'product' AND table_name = 'variants' AND column_name IN ('owner_customer_id', "
                        + "'design_snapshot_id', 'source_variant_id')")).as("the three columns are gone").isZero();

                assertThat(RawSql.texts(db, "SELECT id || ':' || sku || ':' || variant_id FROM inventory.inventory_items"))
                        .as("the ordered SKU's item stays as it was; the unordered SKU's and the blank's go")
                        .containsExactly(ORDERED_ITEM + ":UPG-CUP-ORD:" + BLANK);

                assertThat(RawSql.texts(db, "SELECT conrelid::regclass || '.' || conname FROM pg_constraint "
                        + "WHERE contype = 'f' AND confrelid = 'product.customer_skus'::regclass ORDER BY 1"))
                        .as("every reference to a customer's SKU points at its table")
                        .containsExactlyInAnyOrder(
                                "catalog.customer_price.fk_customer_price_customer_sku",
                                "chat.conversation_context.fk_conversation_context_customer_sku",
                                "inventory.inventory_items.fk_inventory_items_customer_sku",
                                "ordering.order_fee_line.fk_order_fee_line_sku",
                                "ordering.order_line.fk_order_line_customer_sku",
                                "ordering.quote_fee_line.fk_quote_fee_line_sku",
                                "ordering.quote_line.fk_quote_line_customer_sku",
                                "ordering.return_request_line.fk_return_request_line_customer_sku",
                                "procurement.goods_receipt_return_loose_lines.fk_goods_receipt_return_loose_lines_customer_sku",
                                "reporting.product_sales_summary.fk_product_sales_summary_customer_sku");
                assertThat(RawSql.text(db, "SELECT customer_sku_id::text FROM chat.conversation_context WHERE id = ?",
                        CONTEXT)).as("the chat card names the SKU by the id it had").isEqualTo(ORDERED.toString());
                assertThat(RawSql.number(db, "SELECT (SELECT count(*) FROM ordering.quote_line WHERE sku = "
                        + "'UPG-CUP-ORD') + (SELECT count(*) FROM ordering.order_line WHERE sku = 'UPG-CUP-ORD') "
                        + "+ (SELECT count(*) FROM catalog.customer_price WHERE sku = 'UPG-CUP-ORD') "
                        + "+ (SELECT count(*) FROM ordering.return_request_line WHERE sku = 'UPG-CUP-ORD')"))
                        .as("the documents naming the SKU are kept").isEqualTo(4);

                RawSql.refusedBy(db, "packed as its SKU's catalogue variant", "an item for the catalogue's blank",
                        "INSERT INTO inventory.inventory_items (id, sku, variant_id) VALUES (gen_random_uuid(), "
                                + "'UPG-CUP', ?)", BLANK);
                RawSql.refusedBy(db, "is not a SKU of customer", "a customer price of the blank",
                        "INSERT INTO catalog.customer_price (id, customer_id, sku, currency, created_at) VALUES "
                                + "(gen_random_uuid(), ?, 'UPG-CUP', 'VND', NOW())", CUSTOMER);
                RawSql.refusedBy(db, "is a customer's SKU", "a variant taking a customer's SKU's code",
                        "INSERT INTO product.variants (id, product_id, sku, name, status, is_default, "
                                + "attribute_signature, position, version, created_at) VALUES (gen_random_uuid(), ?, "
                                + "'UPG-CUP-NEW', 'clash', 'DRAFT', FALSE, 'CLASH', 9, 0, NOW())", PRODUCT);
                RawSql.refusedBy(db, "immutable", "a customer's SKU moved to another variant",
                        "UPDATE product.customer_skus SET variant_id = gen_random_uuid() WHERE id = ?", UNORDERED);
                assertThat(RawSql.update(db, "INSERT INTO inventory.inventory_items (id, sku, variant_id) VALUES "
                        + "(gen_random_uuid(), 'UPG-CUP-NEW', ?)", BLANK))
                        .as("the unordered SKU gets its item when an order names it").isEqualTo(1);
                assertThat(RawSql.update(db, "INSERT INTO inventory.uom_conversions (id, variant_id, unit_of_measure, "
                        + "factor, active) VALUES (gen_random_uuid(), ?, 'EXTRA', 5, FALSE)", BLANK))
                        .as("a carton is still the catalogue variant's").isEqualTo(1);
            }
        }
    }

    private static void seed(Connection db) throws SQLException {
        // Written with triggers and foreign keys off, as the old rules let them be: what is tested is the migration on
        // rows that existed. CHECKs still hold. The rows the migration's new foreign keys check (the customer, the
        // snapshots, the product and its variant) are real.
        RawSql.update(db, "SET session_replication_role = replica");
        RawSql.update(db, "INSERT INTO product.products (id, code, name, slug, status, kind, version, created_at) "
                + "VALUES (?, 'UPGCUP', 'upgrade cup', 'upg-cup', 'DRAFT', 'CUSTOMIZABLE', 0, NOW())", PRODUCT);
        RawSql.update(db, "INSERT INTO customer.customer (id, email, full_name, status, created_at) VALUES "
                + "(?, 'upg@khachhang.example', 'Upgrade customer', 'ACTIVE', NOW())", CUSTOMER);
        String snapshot = "INSERT INTO design.design_snapshot (id, draft_id, checksum, artifact_url, confirmed_at, "
                + "artifact_manifest, created_at) VALUES (?, gen_random_uuid(), 'c', 'https://example.com/p.pdf', NOW(), "
                + "'[]'::jsonb, NOW())";
        RawSql.update(db, snapshot, ORDERED_SNAPSHOT);
        RawSql.update(db, snapshot, UNORDERED_SNAPSHOT);

        RawSql.update(db, "INSERT INTO product.variants (id, product_id, sku, name, status, is_default, "
                + "attribute_signature, position, version, created_at) VALUES (?, ?, 'UPG-CUP', 'cup', 'ACTIVE', TRUE, "
                + "'DEFAULT', 0, 0, NOW())", BLANK, PRODUCT);
        String own = "INSERT INTO product.variants (id, product_id, sku, name, status, is_default, attribute_signature, "
                + "position, version, created_at, owner_customer_id, design_snapshot_id, source_variant_id) "
                + "VALUES (?, ?, ?, ?, 'ACTIVE', FALSE, ?, ?, 0, NOW(), ?, ?, ?)";
        RawSql.update(db, own, ORDERED, PRODUCT, "UPG-CUP-ORD", "cup ordered", "OWNED-SKU:UPG-CUP-ORD", 1, CUSTOMER,
                ORDERED_SNAPSHOT, BLANK);
        RawSql.update(db, own, UNORDERED, PRODUCT, "UPG-CUP-NEW", "cup never ordered", "OWNED-SKU:UPG-CUP-NEW", 2,
                CUSTOMER, UNORDERED_SNAPSHOT, BLANK);

        // The items tg_variant_inventory_item made, every one packed as the blank; and the blank's carton.
        String item = "INSERT INTO inventory.inventory_items (id, sku, variant_id, created_at) VALUES (?, ?, ?, NOW())";
        RawSql.update(db, item, UUID.randomUUID(), "UPG-CUP", BLANK);
        RawSql.update(db, item, ORDERED_ITEM, "UPG-CUP-ORD", BLANK);
        RawSql.update(db, item, UUID.randomUUID(), "UPG-CUP-NEW", BLANK);
        RawSql.update(db, "INSERT INTO inventory.uom_conversions (id, variant_id, unit_of_measure, factor, weight_kg, "
                + "length_cm, width_cm, height_cm, active, created_at) VALUES (?, ?, 'THUNG', 1000, 8.6, 60, 40, 50, "
                + "TRUE, NOW())", CARTON, BLANK);

        // What names the ordered SKU: its quote line, its order line, its customer price, a return request line and a
        // chat card.
        RawSql.update(db, "INSERT INTO ordering.quote_line (id, quote_id, line_no, sku, uom_conversion_id, cartons, "
                + "units_per_carton) VALUES (gen_random_uuid(), gen_random_uuid(), 1, 'UPG-CUP-ORD', ?, 2, 1000)", CARTON);
        UUID orderLine = UUID.randomUUID();
        RawSql.update(db, "INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency) VALUES "
                + "(?, gen_random_uuid(), 'UPG-CUP-ORD', 2000, 1200, 'VND')", orderLine);
        RawSql.update(db, "INSERT INTO catalog.customer_price (id, customer_id, sku, currency, created_at) VALUES "
                + "(gen_random_uuid(), ?, 'UPG-CUP-ORD', 'VND', NOW())", CUSTOMER);
        RawSql.update(db, "INSERT INTO ordering.return_request_line (id, return_request_id, order_line_id, sku, "
                + "quantity, unit_price, custom_printed) VALUES (gen_random_uuid(), gen_random_uuid(), ?, "
                + "'UPG-CUP-ORD', 1000, 1200, TRUE)", orderLine);
        RawSql.update(db, "INSERT INTO chat.conversation_context (id, conversation_id, kind, variant_id, attached_at, "
                + "created_at) VALUES (?, gen_random_uuid(), 'CUSTOMER_SKU', ?, NOW(), NOW())", CONTEXT, ORDERED);
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
