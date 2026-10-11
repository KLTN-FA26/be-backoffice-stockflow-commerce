package com.stockflow.procurement.internal.domain;

/** How a purchase order was closed ({@code ck_purchase_orders_close_kind}). */
public enum CloseKind {
    /** Everything was received. */
    NORMAL,
    /** Part was received; the rest is written off with a reason. */
    SHORT_CLOSE,
    /** Closed with a reason although the supplier never delivered. Not offered yet. */
    FORCE_CLOSE
}
