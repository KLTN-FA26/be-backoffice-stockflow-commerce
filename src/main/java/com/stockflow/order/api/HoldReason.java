package com.stockflow.order.api;

/**
 * Why an order is ON_HOLD (kltn-docs 17 §5 and edge cases, 09 edge "sai địa chỉ"; WBS 3.17.6.1).
 *
 * <p>{@code CREDIT} is set by the system - an order placed over its credit limit - and decided through credit
 * approval. The rest are raised by Sales and resolved by them, with a note (sales-order-holds, team plan 5-7). No
 * design is checked at
 * packing any more (kltn-docs 08 §1): the DESIGN_MISMATCH hold went, and V20261108000500 resolved the orders it held.</p>
 *
 * <p>{@code DELIVERY_FAILED} is set by the system too (WF-05 fix plan, sections 2-3): a shipment of the order came back
 * from the carrier. It is not resolved with a note like the others: Sales decide its delivery failure case - deliver the
 * goods again, or cancel what was not delivered - and the decision lifts the hold.</p>
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
    OTHER,
    /**
     * A shipment came back from the carrier (09 §4.3 step 3): the order waits for Sales to deliver again or cancel what
     * was not delivered. Raised by the shipment's outcome, lifted by that decision.
     */
    DELIVERY_FAILED;

    /** Whether the coordinator may raise it by hand ({@code POST /orders/{id}/hold}). */
    public boolean raisedByHand() {
        return this != CREDIT && this != DELIVERY_FAILED;
    }

    /** Whether it is resolved by a note ({@code POST /orders/{id}/hold-resolution}) rather than by a decision of its own. */
    public boolean resolvedByNote() {
        return this != CREDIT && this != DELIVERY_FAILED;
    }
}
