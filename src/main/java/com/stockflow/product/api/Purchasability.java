package com.stockflow.product.api;

/**
 * Whether a SKU may be put on a <b>new</b> quote or order line (kltn-docs 01 BR-04): its product is
 * {@code APPROVED} or {@code PUBLISHED}, the SKU is {@code ACTIVE}, and - for a customer's own SKU - so is the
 * catalogue variant its design is drawn on: a SKU is not sold while its blank is not, the rule issuing it and
 * activating it again already apply. Anything else names the reason, which
 * the screen puts next to the line. The name is the one it had when purchase order lines asked it too; they went
 * with purchasing (4A-8).
 *
 * <p>It is not asked of a line that is already open: goods on their way are received whatever happened to
 * the product since (kltn-docs 01 BR-14).</p>
 */
public enum Purchasability {
    PURCHASABLE,
    /** Suspended: not shown, no new quotes or orders. */
    PRODUCT_INACTIVE,
    PRODUCT_DISCONTINUED,
    /** Still a draft, or waiting for approval. */
    PRODUCT_NOT_APPROVED,
    /** The SKU itself is not {@code ACTIVE} (blocked or retired), or no variant or customer's SKU carries it. */
    VARIANT_NOT_ACTIVE,
    /**
     * A customer's own SKU, {@code ACTIVE} itself, drawn on a catalogue variant that is not: the blank is off sale
     * (no paper, the producer stopped the size). Told apart from {@link #VARIANT_NOT_ACTIVE} so Sales sees whether
     * the SKU was stopped or its blank was; the SKU keeps its own status and sells again once the blank does.
     */
    SOURCE_VARIANT_NOT_ACTIVE;

    public boolean isPurchasable() {
        return this == PURCHASABLE;
    }
}
