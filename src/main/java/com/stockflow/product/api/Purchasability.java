package com.stockflow.product.api;

/**
 * Whether a SKU may be put on a <b>new</b> purchase order line (kltn-docs 01 BR-04, 02 BR-01): its
 * product is {@code APPROVED} or {@code PUBLISHED} and its variant is {@code ACTIVE}. Anything else
 * names the reason, which a purchase order screen puts next to the line.
 *
 * <p>It is not asked of a line that is already open: goods on their way are received whatever happened to
 * the product since (kltn-docs 02 edge cases), and neither is a subcontract order, which belongs to a
 * production order that is already running.</p>
 */
public enum Purchasability {
    PURCHASABLE,
    /** Suspended: not shown, no new purchase orders. */
    PRODUCT_INACTIVE,
    PRODUCT_DISCONTINUED,
    /** Still a draft, or waiting for approval. */
    PRODUCT_NOT_APPROVED,
    /** The SKU is a variant that is not {@code ACTIVE}, or no variant carries it. */
    VARIANT_NOT_ACTIVE;

    public boolean isPurchasable() {
        return this == PURCHASABLE;
    }
}
