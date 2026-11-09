package com.stockflow.order.api;

/**
 * A fee line of a quote, an order and a contract (kltn-docs 14 BR-09, BR-17, BR-20; team plan 4B-3): no cartons, no
 * MOQ or tier, held nowhere and in no production order - an amount Sales enters, with its VAT.
 */
public enum FeeKind {
    /** The plate (printing die) of a SKU produced for the first time; one per such SKU (14 BR-17). */
    PLATE_FEE,
    /** What the customer pays for the delivery, as agreed; at most one (14 BR-20). */
    SHIPPING_FEE
}
