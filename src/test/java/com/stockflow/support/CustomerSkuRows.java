package com.stockflow.support;

import com.stockflow.common.id.Identifiers;
import jakarta.persistence.EntityManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/**
 * A customer's own SKU as the design approval leaves it (kltn-docs 12 §4.5, 01 §4.8, BR-03; 2F-7): an approved
 * customizable product, a design snapshot of the customer's, the SKU drawn on the product's default variant and owned by
 * the customer, and its carton unit - what quotes and orders name since 4B-2 (14 BR-04, BR-08). Written as rows, the way
 * {@link ReferenceRows} writes another module's: issuing through {@code CustomerSkus.issue} is
 * {@code CustomerSkuIntegrationTest}'s subject, and a test about quotes needs only what it leaves behind.
 */
public final class CustomerSkuRows {

    /**
     * @param uomConversionId its carton unit
     * @param unitsPerCarton  the carton unit's factor
     */
    public record Issued(String sku, UUID variantId, UUID productId, UUID snapshotId, UUID uomConversionId,
                         int unitsPerCarton) {
    }

    private CustomerSkuRows() {
    }

    /**
     * A SKU of {@code customerId} sold in cartons of {@code unitsPerCarton}, on a product of its own whose tax class is
     * EXEMPT (no VAT), produced before (an order of it was confirmed): a quote of it has no plate fee and its totals are
     * its lines' - what a test about something else wants (4B-3).
     */
    public static Issued issue(JdbcTemplate jdbc, TransactionTemplate tx, EntityManager em, UUID customerId,
                               int unitsPerCarton) {
        return issue(jdbc, tx, em, customerId, unitsPerCarton, "EXEMPT", true);
    }

    /**
     * The same, with the product's tax class ({@code STANDARD}, {@code REDUCED}, {@code EXEMPT} or null for none yet)
     * and whether the SKU was produced before - a SKU never produced gets a plate fee on its first quote (14 BR-17).
     */
    public static Issued issue(JdbcTemplate jdbc, TransactionTemplate tx, EntityManager em, UUID customerId,
                               int unitsPerCarton, String taxClass, boolean produced) {
        ReferenceRows.ProductRow product = tx.execute(s -> ReferenceRows.approvedProduct(em));
        tx.executeWithoutResult(s -> jdbc.update("UPDATE product.products SET tax_class = ? WHERE id = ?", taxClass,
                product.productId()));
        Issued issued = issueOn(jdbc, tx, customerId, product, unitsPerCarton);
        if (produced) {
            produced(jdbc, tx, customerId, issued.sku());
        }
        return issued;
    }

    /**
     * Marks {@code sku} produced before (4B-3, 14 BR-17): a confirmed credit order of it, as an earlier sale left it -
     * what {@code SkuProductionHistory} reads until production orders exist.
     */
    public static void produced(JdbcTemplate jdbc, TransactionTemplate tx, UUID customerId, String sku) {
        UUID order = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount,
                        currency, placed_at, created_at, payment_term, credit_term_days)
                    VALUES (?, ?, ?, ?, 'CONFIRMED', 0, 'VND', NOW() - interval '30 days', NOW() - interval '30 days',
                        'CREDIT', 30)""", order, ReferenceRows.orderNumber(order), customerId, Identifiers.newId());
            jdbc.update("INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency) "
                    + "VALUES (?, ?, ?, 1, 0, 'VND')", Identifiers.newId(), order, sku);
        });
    }

    /**
     * A SKU of {@code customerId} drawn on {@code product}'s default variant, the product made customizable: a test
     * that changes the product's status (suspends it, discontinues it) sees the SKU follow.
     */
    public static Issued issueOn(JdbcTemplate jdbc, TransactionTemplate tx, UUID customerId,
                                 ReferenceRows.ProductRow product, int unitsPerCarton) {
        return tx.execute(s -> {
            jdbc.update("UPDATE product.products SET kind = 'CUSTOMIZABLE' WHERE id = ?", product.productId());
            UUID draft = Identifiers.newId();
            UUID snapshot = Identifiers.newId();
            jdbc.update("""
                    INSERT INTO design.design_draft (id, customer_id, product_id, status, created_at, current_artifacts)
                    VALUES (?, ?, ?, 'DRAFT', NOW(), '{}'::jsonb)""", draft, customerId, product.productId());
            jdbc.update("""
                    INSERT INTO design.design_snapshot (id, draft_id, checksum, artifact_url, confirmed_at, created_at,
                        artifact_manifest)
                    VALUES (?, ?, ?, 'https://example.com/print.pdf', NOW(), NOW(), '[]'::jsonb)""",
                    snapshot, draft, "c".repeat(64));
            UUID variant = Identifiers.newId();
            String sku = product.sku() + "-C" + variant.toString().substring(24, 28).toUpperCase();
            jdbc.update("""
                    INSERT INTO product.variants (id, product_id, sku, name, status, is_default, attribute_signature,
                        position, version, created_at, owner_customer_id, design_snapshot_id, source_variant_id)
                    VALUES (?, ?, ?, 'Ly in logo khach', 'ACTIVE', FALSE, ?, 1, 0, NOW(), ?, ?, ?)""",
                    variant, product.productId(), sku, "OWNED-SKU:" + sku, customerId, snapshot, product.variantId());
            UUID item = jdbc.queryForObject("SELECT id FROM inventory.inventory_items WHERE sku = ?", UUID.class, sku);
            UUID carton = Identifiers.newId();
            jdbc.update("""
                    INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor, is_base,
                        weight_kg, length_cm, width_cm, height_cm, default_purchase, active, version, created_at,
                        default_shipping)
                    VALUES (?, ?, 'CARTON', ?, FALSE, 4.2, 60, 40, 35, FALSE, TRUE, 0, NOW(), TRUE)""",
                    carton, item, unitsPerCarton);
            return new Issued(sku, variant, product.productId(), snapshot, carton, unitsPerCarton);
        });
    }

    /**
     * {@code units} pieces of {@code sku} free in HCM, received a while ago, as whole sealed cartons of its carton unit
     * (what available to promise counts since team plan 4C-2); a remainder short of a carton is not stocked.
     */
    public static void stock(JdbcTemplate jdbc, TransactionTemplate tx, String sku, int units) {
        tx.executeWithoutResult(s -> {
            UUID unit = CartonRows.unit(jdbc, sku, units);
            int factor = jdbc.queryForObject("SELECT factor FROM inventory.uom_conversions WHERE id = ?",
                    Integer.class, unit);
            CartonRows.stock(jdbc, sku, "HCM-A02-1-A", units / factor, factor, null,
                    java.time.Instant.now().minus(java.time.Duration.ofDays(2)));
        });
    }
}
