package com.stockflow.product.internal.domain;

/**
 * Lifecycle of a product in the master. Mapped {@code EnumType.STRING}.
 */
public enum ProductStatus {

    /** Being set up; not published to the catalog. */
    DRAFT,

    /** Published and orderable. */
    ACTIVE,

    /** No longer sold; kept for the order history that references it. */
    DISCONTINUED
}
