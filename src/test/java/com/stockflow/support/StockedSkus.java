package com.stockflow.support;

import com.stockflow.common.id.Identifiers;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/**
 * A SKU that can hold stock, for a test whose subject is the warehouse rather than the catalogue: a catalogue variant of
 * the test's product (code {@code <sku>-BLANK}) and, drawn on it, a customer's SKU with the test's code, with the
 * inventory item an order of it would have made (owner decisions Q4, Q5 of 2026-11-26, V20270105000100). Only a
 * customer's SKU has an item and holds stock; its cartons are its variant's ({@code inventory_items.variant_id}), so a
 * test that declares a carton through the item ({@code select variant_id from inventory.inventory_items ...}) declares it
 * on the variant, as before. The owner is the demo customer; the design is a bare draft and snapshot, which is all the
 * foreign keys ask.
 *
 * <p>Written as rows, the way {@code CustomerSkuRows} writes a customer's SKU: issuing one is
 * {@code CustomerSkuIntegrationTest}'s subject.</p>
 */
public final class StockedSkus {

    private StockedSkus() {
    }

    /**
     * A customer's SKU {@code sku} on {@code productId} (a product the test inserted), drawn on a new catalogue variant
     * of it, with its item.
     *
     * @return the item id
     */
    public static UUID customerSkuOn(JdbcTemplate jdbc, UUID productId, String sku, String name) {
        return customerSkuOn(jdbc, productId, Identifiers.newId(), sku, name);
    }

    /** The same, the catalogue variant given the id {@code variantId}. */
    public static UUID customerSkuOn(JdbcTemplate jdbc, UUID productId, UUID variantId, String sku, String name) {
        jdbc.update("insert into product.variants(id,product_id,sku,name,attribute_signature) values (?,?,?,?,'')",
                variantId, productId, sku + "-BLANK", name);
        return customerSkuOf(jdbc, DemoData.CUSTOMER_ID, productId, variantId, sku, name);
    }

    /**
     * A customer's SKU {@code sku} of {@code customerId}, drawn on the catalogue variant {@code variantId} of
     * {@code productId} that exists already, with its item.
     *
     * @return the item id
     */
    public static UUID customerSkuOf(JdbcTemplate jdbc, UUID customerId, UUID productId, UUID variantId, String sku,
                                     String name) {
        UUID draft = Identifiers.newId();
        UUID snapshot = Identifiers.newId();
        jdbc.update("""
                insert into design.design_draft (id, customer_id, product_id, status, created_at, current_artifacts)
                values (?, ?, ?, 'DRAFT', now(), '{}'::jsonb)""", draft, customerId, productId);
        jdbc.update("""
                insert into design.design_snapshot (id, draft_id, checksum, artifact_url, confirmed_at, created_at,
                    artifact_manifest)
                values (?, ?, ?, 'https://example.com/print.pdf', now(), now(), '[]'::jsonb)""",
                snapshot, draft, "c".repeat(64));
        jdbc.update("""
                insert into product.customer_skus (id, product_id, variant_id, customer_id, design_snapshot_id, sku, name)
                values (?, ?, ?, ?, ?, ?, ?)""", Identifiers.newId(), productId, variantId, customerId,
                snapshot, sku, name);
        UUID item = Identifiers.newId();
        jdbc.update("insert into inventory.inventory_items (id, sku, variant_id) values (?, ?, ?)", item, sku,
                variantId);
        return item;
    }
}
