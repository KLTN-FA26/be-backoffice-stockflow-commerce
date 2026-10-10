package com.stockflow.procurement.internal.domain;

/**
 * Lifecycle of a purchase order, decision D4 ({@code ck_purchase_orders_status}).
 *
 * <pre>
 *   DRAFT ──submit──▶ PENDING_APPROVAL ──approve (four-eyes)──▶ APPROVED ──confirm (= send)──▶ CONFIRMED
 *     ▲                     │                                                                   │
 *     └──────reject─────────┘                                         goods receipts (SCRUM-435)│
 *                                                                                               ▼
 *                                           CLOSED ◀──close── RECEIVED ◀── PARTIALLY_RECEIVED ◀─┘
 *                                             ▲                                  │
 *                                             └──────────close short─────────────┘
 *
 *   DRAFT / PENDING_APPROVAL / APPROVED / CONFIRMED ──cancel──▶ CANCELLED   (nothing received)
 * </pre>
 *
 * <p>The old model's {@code SENT} is {@code CONFIRMED}: confirming the order locks its content and
 * sends it to the supplier in one step (C4 plan Q1). The supplier's answer is recorded beside the
 * status ({@link SupplierConfirmationStatus}), it does not move it. {@code CLOSED_SHORT} is
 * {@code CLOSED} with {@link CloseKind#SHORT_CLOSE} (Q2). Receiving moves CONFIRMED and
 * PARTIALLY_RECEIVED forward; that is the goods receipt's own transition, not an edge of this table.</p>
 */
public enum PurchaseOrderStatus {
    DRAFT, PENDING_APPROVAL, APPROVED, CONFIRMED, PARTIALLY_RECEIVED, RECEIVED, CLOSED, CANCELLED;

    public boolean canTransitionTo(PurchaseOrderStatus target) {
        return switch (this) {
            case DRAFT -> target == PENDING_APPROVAL || target == CANCELLED;
            case PENDING_APPROVAL -> target == APPROVED || target == DRAFT || target == CANCELLED;
            case APPROVED -> target == CONFIRMED || target == CANCELLED;
            case CONFIRMED -> target == CANCELLED;
            case PARTIALLY_RECEIVED, RECEIVED -> target == CLOSED;
            case CLOSED, CANCELLED -> false;
        };
    }

    /** Still to be received or still on its way to the supplier: a supplier cannot be retired under it. */
    public boolean isOpen() {
        return this != RECEIVED && this != CLOSED && this != CANCELLED;
    }
}
