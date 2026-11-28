package com.stockflow.order.internal.domain;

/**
 * The operational work on an order that strict mode does not block (kltn-docs 17 BR-32; 14 §4.4 step 18): done by
 * someone other than the customer's owner or the owner's cover, it is recorded in the order's history as done on their
 * behalf ("làm hộ") and they are told. Stored as text in {@code ordering.order_on_behalf_action.action}, whose CHECK
 * lists the same names.
 */
public enum OnBehalfAction {
    /** 17 §4.2: the shipping warehouse chosen. */
    CHOOSE_SHIPPING_WAREHOUSE,
    /** 17 §5: the order put on hold by hand. */
    HOLD,
    /** 17 §5: its hold lifted. */
    RESOLVE_HOLD,
    /** WF-05: its completion held (a claim still open). */
    HOLD_COMPLETION,
    /** WF-05: the completion hold released. */
    RELEASE_COMPLETION_HOLD,
    /** 17 §4.8: the customer's cancellation request approved. */
    APPROVE_CANCELLATION_REQUEST,
    /** 17 §4.8: the customer's cancellation request rejected. */
    REJECT_CANCELLATION_REQUEST,
    /** 17 §4.8: the order cancelled from the back office. */
    CANCEL_ORDER,
    /** G3-2: the rest of a partly fulfilled order closed. */
    CLOSE_REMAINDER,
    /** PACK-PARTIAL: partial shipments allowed or stopped. */
    DECIDE_PARTIAL_SHIPMENT,
    /** WF-05 §3: what a failed delivery left undelivered cancelled. */
    CANCEL_UNDELIVERED,
    /** 17 BR-16: the delivery address changed. */
    CHANGE_SHIPPING_ADDRESS,
    /**
     * 17 §4.14 step 58: the option the customer chose on a shortage card carried out - one entry for the press of
     * "Thực hiện", not one per step it runs.
     */
    EXECUTE_SHORTAGE_CHOICE
}
