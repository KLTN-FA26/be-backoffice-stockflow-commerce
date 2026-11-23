package com.stockflow.order.internal.domain;

/**
 * What a production order is drawn up for (kltn-docs 17 §4.3 step 6, §4.13; {@code ck_production_order_kind}).
 */
public enum ProductionOrderKind {

    /** What a delivery batch lacks after the stock held for it (17 BR-05). */
    STANDARD,

    /** Cartons of another production order's goods written off as damaged after they were received (17 BR-31). */
    REPLACEMENT
}
