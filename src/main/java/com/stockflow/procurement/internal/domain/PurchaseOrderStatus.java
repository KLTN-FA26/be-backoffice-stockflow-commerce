package com.stockflow.procurement.internal.domain;

/**
 * Lifecycle of a purchase order, decision D4 ({@code ck_purchase_orders_status}).
 *
 * <pre>
 *   DRAFT ──submit, total above the submitter's limit──▶ PENDING_APPROVAL ──approve (four-eyes, approver's limit)──┐
 *     │  ▲                                                      │                                                │
 *     │  └──────────────────reject (reason)────────────────────┘                                                ▼
 *     └──submit, total within the submitter's own limit (auto-approved)──────────────────────────────────▶ APPROVED
 *                                                                                                                │ confirm (= send)
 *                                                                                                                ▼
 *   CLOSED ◀──close── RECEIVED ◀── PARTIALLY_RECEIVED ◀──goods receipts (SCRUM-435)── CONFIRMED
 *     ▲  ▲                                  │                                              │
 *     │  └──────────close short─────────────┘                                              │
 *     └──────────────────────force close (reason; nothing was received)────────────────────┘
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
            // DRAFT -> APPROVED is the under-limit submission only (PurchaseOrder#submitWithinLimit); approve() refuses a draft.
            case DRAFT -> target == PENDING_APPROVAL || target == APPROVED || target == CANCELLED;
            case PENDING_APPROVAL -> target == APPROVED || target == DRAFT || target == CANCELLED;
            case APPROVED -> target == CONFIRMED || target == CANCELLED;
            // CONFIRMED -> CLOSED is the force close: the order will not be received at all (PurchaseOrder#closeShort).
            case CONFIRMED -> target == CANCELLED || target == CLOSED;
            case PARTIALLY_RECEIVED, RECEIVED -> target == CLOSED;
            case CLOSED, CANCELLED -> false;
        };
    }

    /**
     * Approved and not finished: an amendment asked of an order in this status can still be decided (SCRUM-117). A
     * standard order narrows it further - nothing received yet ({@code PurchaseOrder#requireAmendable}) - while a
     * subcontract supplement may be asked for through receipt. On a CANCELLED or CLOSED order a revision still named
     * pending is one nobody can decide any more, so it is not shown or treated as a pending amendment.
     */
    public boolean takesAmendments() {
        return this == APPROVED || this == CONFIRMED || this == PARTIALLY_RECEIVED || this == RECEIVED;
    }

    /** The names of the statuses that {@linkplain #takesAmendments take amendments}, for a query's IN list. */
    public static java.util.List<String> takingAmendments() {
        return java.util.Arrays.stream(values()).filter(PurchaseOrderStatus::takesAmendments).map(Enum::name).toList();
    }

    /** Still to be received or still on its way to the supplier: a supplier cannot be retired under it. */
    public boolean isOpen() {
        return this != RECEIVED && this != CLOSED && this != CANCELLED;
    }
}
