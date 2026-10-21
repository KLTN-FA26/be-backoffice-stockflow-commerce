package com.stockflow.order.api;

/**
 * Why an order is ON_HOLD (kltn-docs 17 §5 and edge cases, 09 edge "sai địa chỉ"; WBS 3.17.6.1).
 *
 * <p>{@code CREDIT} and {@code DESIGN_MISMATCH} are set by the system - an order placed over its credit limit, a
 * printed design whose checksum does not match at packing - and resolved through their own flows (credit approval,
 * QC reverification). The rest are raised by the Order Coordinator and resolved by them, with a note.</p>
 */
public enum HoldReason {
    /** Over the customer's credit limit, or overdue: decided through credit approval (15 §4.3). */
    CREDIT,
    /** A printed design did not match its confirmed checksum at packing (BR-DSG-003). */
    DESIGN_MISMATCH,
    /** A payment question to settle before going on. */
    PAYMENT,
    /** The delivery address is wrong or the carrier does not serve it (09 edge). */
    ADDRESS,
    /** Not enough stock to allocate, or a short pick (17 edge). */
    INVENTORY,
    /** Waiting for the customer to confirm a change. */
    CUSTOMER,
    /** Suspected fraud. */
    FRAUD,
    /** Anything else; the note must say what. */
    OTHER;

    /** Whether the coordinator may raise it by hand ({@code POST /orders/{id}/hold}). */
    public boolean raisedByHand() {
        return this != CREDIT && this != DESIGN_MISMATCH;
    }
}
