package com.stockflow.procurement.internal.domain;

/**
 * Lifecycle of a purchase order. {@code CLOSED_SHORT} = closed with less received than ordered.
 *
 * <p><b>Scope note (SCRUM-113/116/119, WBS 3.2.1/3.2.4/3.2.7):</b> this is a simplified subset of
 * the full ten-state machine in {@code docs/business-design/03-state-machines.md} §2. It merges
 * away {@code PENDING_APPROVAL} (submit+approve is one step, {@code approve()}), {@code CONFIRMED}
 * (no separate "supplier acknowledged" step), and {@code RECEIVED} (reaching zero open quantity
 * closes the PO directly rather than waiting for a three-way-matched invoice, BR-INV-003 — that
 * gate needs {@code supplier_invoice} matching logic this sprint does not build). Flagged here
 * rather than silently narrowed, since a future story implementing approval limits (BR-PO-002) or
 * invoice matching will need to widen this enum and every switch over it.</p>
 *
 * <pre>
 *   DRAFT ──approve──▶ APPROVED ──send──▶ SENT ──receiveGoods──▶ PARTIALLY_RECEIVED ──closeShort──▶ CLOSED_SHORT
 *                                           │                         │
 *                                           │                         └──receiveGoods (open qty = 0)──▶ CLOSED
 *                                           └──receiveGoods (open qty = 0)──▶ CLOSED
 *
 *   DRAFT/APPROVED/SENT ──cancel──▶ CANCELLED   (only while nothing has been received)
 * </pre>
 */
public enum PurchaseOrderStatus {
    DRAFT, APPROVED, SENT, PARTIALLY_RECEIVED, CLOSED, CLOSED_SHORT, CANCELLED
}
