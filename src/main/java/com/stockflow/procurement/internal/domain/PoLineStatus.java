package com.stockflow.procurement.internal.domain;

/** Receiving state of one purchase order line ({@code ck_purchase_order_lines_status}). */
public enum PoLineStatus {
    OPEN, PARTIALLY_RECEIVED, RECEIVED, CLOSED, CANCELLED
}
