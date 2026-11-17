package com.stockflow.product.api;

/**
 * Whether a SKU may be put on a <b>new</b> quote or order line (kltn-docs 01 BR-04): its product is
 * {@code APPROVED} or {@code PUBLISHED} and its variant is {@code ACTIVE}. Anything else names the reason, which
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
    /** The SKU is a variant that is not {@code ACTIVE}, or no variant carries it. */
    VARIANT_NOT_ACTIVE;

    public boolean isPurchasable() {
        return this == PURCHASABLE;
    }
}
