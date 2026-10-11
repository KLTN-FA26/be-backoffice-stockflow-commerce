package com.stockflow.product.api;

/**
 * What a product is sold as. A {@link #CUSTOMIZABLE} product is printed to the customer's design
 * (cups, packaging) and can carry customization templates and design drafts; a {@link #STANDARD}
 * one is sold as it is.
 */
public enum ProductKind {
    STANDARD,
    CUSTOMIZABLE
}
