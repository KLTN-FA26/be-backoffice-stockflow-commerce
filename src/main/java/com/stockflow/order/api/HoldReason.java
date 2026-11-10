package com.stockflow.order.api;

/**
 * Why an order is ON_HOLD (kltn-docs 17 §5 and edge cases, 09 edge "sai địa chỉ"; WBS 3.17.6.1).
 *
 * <p>{@code CREDIT} is set by the system - an order placed over its credit limit - and decided through credit
 * approval. The rest are raised by Sales and resolved by them, with a note (sales-order-holds, team plan 5-7). No
 * design is checked at
 * packing any more (kltn-docs 08 §1): the DESIGN_MISMATCH hold went, and V20261108000500 resolved the orders it held.</p>
 */
public enum HoldReason {
    /** Over the customer's credit limit, or overdue: decided through credit approval (15 §4.3). */
    CREDIT,
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
        return this != CREDIT;
    }
}
