package com.stockflow.support;

import com.stockflow.common.id.Identifiers;
import jakarta.persistence.EntityManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/**
 * A customer's own SKU as the design approval leaves it (kltn-docs 12 §4.5, 01 §4.8, BR-03; 2F-7): an approved
 * customizable product, a design snapshot of the customer's, the SKU - a row of {@code product.customer_skus} since
 * V20270105000100 (owner decision Q4) - drawn on the product's default variant and owned by the customer, packed in that
 * variant's carton unit (owner decision Q1: the SKU has none of its own) - what quotes and orders name since 4B-2 (14
 * BR-04, BR-08). The approval makes no inventory item (Q5): a SKU {@link #produced} before, or stocked through
 * {@link CartonRows}, gets the one its order would have made ({@link CartonRows#item}). Written as rows, the way
 * {@link ReferenceRows} writes another module's: issuing through {@code CustomerSkus.issue} is
 * {@code CustomerSkuIntegrationTest}'s subject, and a test about quotes needs only what it leaves behind.
 */
public final class CustomerSkuRows {

    /**
     * @param id              the SKU's own id ({@code product.customer_skus.id})
     * @param sourceVariantId the catalogue variant it was drawn on, whose carton it is packed in
     * @param uomConversionId its carton unit: its source variant's
     * @param unitsPerCarton  the carton unit's factor
     */
    public record Issued(String sku, UUID id, UUID productId, UUID snapshotId, UUID sourceVariantId,
                         UUID uomConversionId, int unitsPerCarton) {
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
     * what {@code SkuProductionHistory} reads until production orders exist - with the inventory item that order made
     * (owner decision Q5).
     */
    public static void produced(JdbcTemplate jdbc, TransactionTemplate tx, UUID customerId, String sku) {
        UUID order = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            CartonRows.item(jdbc, sku);
            jdbc.update("""
                    INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount,
                        currency, placed_at, created_at, contract_signed_at)
                    VALUES (?, ?, ?, ?, 'CONFIRMED', 0, 'VND', NOW() - interval '30 days', NOW() - interval '30 days',
                        NOW() - interval '30 days')""", order, ReferenceRows.orderNumber(order), customerId, Identifiers.newId());
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
        return issueOn(jdbc, tx, customerId, product, unitsPerCarton, null);
    }

    /**
     * A SKU of {@code customerId}, produced before, issued from an APPROVED design as the design module reads one back
     * (a snapshot with its print file and a confirmer, kltn-docs 12 §4.5): what a quote request on the design reads as
     * approved with its customer SKU (14 BR-22). The design is {@link #designOf}.
     */
    public static Issued issueApproved(JdbcTemplate jdbc, TransactionTemplate tx, EntityManager em, UUID customerId,
                                       int unitsPerCarton) {
        ReferenceRows.ProductRow product = tx.execute(s -> ReferenceRows.approvedProduct(em));
        tx.executeWithoutResult(s -> jdbc.update("UPDATE product.products SET tax_class = 'EXEMPT' WHERE id = ?",
                product.productId()));
        UUID confirmer = tx.execute(s -> ReferenceRows.user(em));
        Issued issued = issueOn(jdbc, tx, customerId, product, unitsPerCarton, confirmer);
        produced(jdbc, tx, customerId, issued.sku());
        return issued;
    }

    /** The design {@code issued} was drawn from. */
    public static UUID designOf(JdbcTemplate jdbc, Issued issued) {
        return jdbc.queryForObject("SELECT draft_id FROM design.design_snapshot WHERE id = ?", UUID.class,
                issued.snapshotId());
    }

    /** {@code approvedBy} null: the design a DRAFT and its snapshot the legacy, unconfirmed shape. */
    private static Issued issueOn(JdbcTemplate jdbc, TransactionTemplate tx, UUID customerId,
                                  ReferenceRows.ProductRow product, int unitsPerCarton, UUID approvedBy) {
        return tx.execute(s -> {
            jdbc.update("UPDATE product.products SET kind = 'CUSTOMIZABLE' WHERE id = ?", product.productId());
            UUID draft = Identifiers.newId();
            UUID snapshot = Identifiers.newId();
            if (approvedBy == null) {
                jdbc.update("""
                        INSERT INTO design.design_draft (id, customer_id, product_id, status, created_at,
                            current_artifacts)
                        VALUES (?, ?, ?, 'DRAFT', NOW(), '{}'::jsonb)""", draft, customerId, product.productId());
                jdbc.update("""
                        INSERT INTO design.design_snapshot (id, draft_id, checksum, artifact_url, confirmed_at,
                            created_at, artifact_manifest)
                        VALUES (?, ?, ?, 'https://example.com/print.pdf', NOW(), NOW(), '[]'::jsonb)""",
                        snapshot, draft, "c".repeat(64));
            } else {
                UUID art = Identifiers.newId();
                jdbc.update("""
                        INSERT INTO design.design_draft (id, customer_id, product_id, status, created_at,
                            current_artifacts, name, reviewed_by, reviewed_at)
                        VALUES (?, ?, ?, 'APPROVED', NOW(), '{}'::jsonb, 'Ly in logo khach', ?, NOW())""", draft,
                        customerId, product.productId(), approvedBy);
                jdbc.update("""
                        INSERT INTO design.design_artifact (id, draft_id, storage_key, original_name, content_type,
                            size_bytes, stored_at, checksum, created_at, artifact_role)
                        VALUES (?, ?, ?, 'art.pdf', 'application/pdf', 1, NOW(), ?, NOW(), 'PRINT_READY')""",
                        art, draft, "design/" + art + "/art.pdf", "c".repeat(64));
                jdbc.update("""
                        INSERT INTO design.design_snapshot (id, draft_id, checksum, artifact_url, confirmed_at,
                            created_at, artifact_manifest, artifact_id, confirmed_by, reviewed_by, spec)
                        VALUES (?, ?, ?, 'https://example.com/print.pdf', NOW(), NOW(), '[]'::jsonb, ?, ?, ?,
                            'Logo front, 2 colours')""",
                        snapshot, draft, "c".repeat(64), art, approvedBy, approvedBy);
            }
            UUID id = Identifiers.newId();
            String sku = product.sku() + "-C" + id.toString().substring(24, 28).toUpperCase();
            jdbc.update("""
                    INSERT INTO product.customer_skus (id, product_id, variant_id, customer_id, design_snapshot_id, sku,
                        name, status, version, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, 'Ly in logo khach', 'ACTIVE', 0, NOW())""",
                    id, product.productId(), product.variantId(), customerId, snapshot, sku);
            // The SKU is packed in its source variant's carton (owner decision Q1), never one of its own.
            UUID carton = CartonRows.ofVariant(jdbc, product.variantId(), unitsPerCarton, "4.2", "60", "40", "35");
            return new Issued(sku, id, product.productId(), snapshot, product.variantId(), carton, unitsPerCarton);
        });
    }

    /**
     * A SKU of {@code customerId} that handles like the catalogue SKU {@code like}: its variant turns the same way
     * (rotatable, on the variant since V20270104000200), its carton has the same size and weight, and it has a barcode
     * of its own. The SKU is issued on a product of its own, so its variant's carton is set here and no other SKU's.
     * What a test of picking, packing or loading needs since every order is a contract's, whose lines are only ever the
     * customer's own SKUs (14 BR-04): the demo catalogue's goods, as the customer's.
     */
    public static Issued like(JdbcTemplate jdbc, TransactionTemplate tx, EntityManager em, UUID customerId, String like,
                              int unitsPerCarton) {
        Issued issued = issue(jdbc, tx, em, customerId, unitsPerCarton);
        String barcode = "29" + String.format("%011d", Math.floorMod(UUID.randomUUID().getLeastSignificantBits(),
                100_000_000_000L));
        tx.executeWithoutResult(s -> {
            CartonRows.item(jdbc, issued.sku());
            jdbc.update("UPDATE inventory.inventory_items SET barcode = ? WHERE sku = ?", barcode, issued.sku());
        });
        UUID likeVariant = tx.execute(s -> CartonRows.variantOf(jdbc, like));
        tx.executeWithoutResult(s -> jdbc.update("""
                UPDATE product.variants v
                   SET rotatable = c.rotatable
                  FROM product.variants c
                 WHERE v.id = ? AND c.id = ?""", issued.sourceVariantId(), likeVariant));
        // Since 4C-6 a parcel is a checked LPN carton, sized by its carton unit (08 BR-07): the issued SKU's carton - its
        // variant's - takes the catalogue SKU's carton size and weight as they are now (a test sets them first,
        // CartonStationSupport), so a load plan counts the customer's cartons as it counted the catalogue's. Kept as
        // issued where the catalogue declares none.
        tx.executeWithoutResult(s -> jdbc.update("""
                UPDATE inventory.uom_conversions u
                   SET length_cm = COALESCE(c.length_cm, u.length_cm), width_cm = COALESCE(c.width_cm, u.width_cm),
                       height_cm = COALESCE(c.height_cm, u.height_cm), weight_kg = COALESCE(c.weight_kg, u.weight_kg)
                  FROM (SELECT cu.length_cm, cu.width_cm, cu.height_cm, cu.weight_kg
                          FROM inventory.uom_conversions cu
                         WHERE cu.variant_id = ? AND cu.active
                         ORDER BY cu.created_at, cu.id LIMIT 1) c
                 WHERE u.id = ?""", likeVariant, issued.uomConversionId()));
        return issued;
    }

    /**
     * 14 BR-10 (2026-11-26): the variant {@code sku} was drawn on loses its standard price, so a quote line of it has
     * nothing to start from but the customer's own price - what a test of "no price applies" needs.
     */
    public static void withoutStandardPrice(JdbcTemplate jdbc, TransactionTemplate tx, String sku) {
        tx.executeWithoutResult(s -> jdbc.update("""
                DELETE FROM catalog.standard_price p
                 USING product.customer_skus c JOIN product.variants s ON s.id = c.variant_id
                 WHERE c.sku = ? AND p.sku = s.sku""", sku));
    }

    /** The barcode of {@code sku}'s item. */
    public static String barcode(JdbcTemplate jdbc, String sku) {
        return jdbc.queryForObject("SELECT barcode FROM inventory.inventory_items WHERE sku = ?", String.class, sku);
    }

    /**
     * {@code units} pieces of {@code sku} free in HCM, received a while ago, as whole sealed cartons of its carton unit
     * (what available to promise counts since team plan 4C-2); a remainder short of a carton is not stocked.
     */
    public static void stock(JdbcTemplate jdbc, TransactionTemplate tx, String sku, int units) {
        tx.executeWithoutResult(s -> {
            CartonRows.item(jdbc, sku);
            UUID unit = CartonRows.unit(jdbc, sku, units);
            int factor = jdbc.queryForObject("SELECT factor FROM inventory.uom_conversions WHERE id = ?",
                    Integer.class, unit);
            java.time.Instant received = java.time.Instant.now().minus(java.time.Duration.ofDays(2));
            if (units % factor == 0) {
                CartonRows.stock(jdbc, sku, "HCM-A01-1-A", units / factor, factor, null, received);
                return;
            }
            // A carton of exactly {@code units} pieces: the unit {@code ConfirmedOrders.Line#units} orders it in.
            UUID item = jdbc.queryForObject("SELECT id FROM inventory.inventory_items WHERE sku = ?", UUID.class, sku);
            // The item has one active carton (01 BR-03): the odd carton is made in its own unit for a moment, then the
            // SKU's carton is its active unit again, so the odd one is still no whole carton of the SKU.
            UUID pack = CartonRows.cartonUnit(jdbc, item, "PK" + units, units);
            CartonRows.stockOfUnit(jdbc, sku, pack, "HCM-A01-1-A", 1, null, received, "AVAILABLE");
            CartonRows.reactivate(jdbc, unit);
        });
    }
}
