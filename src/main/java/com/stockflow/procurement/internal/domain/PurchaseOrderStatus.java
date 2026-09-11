package com.stockflow.procurement.internal.domain;

/** Lifecycle of a purchase order. CLOSED_SHORT = closed with less received than ordered. */
public enum PurchaseOrderStatus {
    DRAFT, APPROVED, SENT, PARTIALLY_RECEIVED, CLOSED, CLOSED_SHORT, CANCELLED
}
