package com.stockflow.product.api;

/**
 * What a product is sold as. Every product is {@link #CUSTOMIZABLE} (kltn-docs 01 BR-19, team plan 5-9): a cup or a
 * packaging printed to the customer's design, whose variants are only the molds a design is printed on; what is stocked,
 * quoted and ordered is the customer's own SKU (01 §4.8). The system sets it; nobody chooses it.
 */
public enum ProductKind {
    /**
     * Sold as it is, from stock. Gone since 5-9 ({@code ck_products_kind} admits CUSTOMIZABLE only and
     * V20261116000200 converted every such row); kept so a value in old history or old events still reads.
     */
    @Deprecated
    STANDARD,
    CUSTOMIZABLE
}
