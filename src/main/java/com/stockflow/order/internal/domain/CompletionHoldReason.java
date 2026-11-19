package com.stockflow.order.internal.domain;

/**
 * Why an order's automatic completion is paused (WF-05 GH-2); {@code ck_order_completion_hold_reason} holds the same
 * names.
 */
public enum CompletionHoldReason {

    /**
     * The customer says goods the carrier reported delivered did not arrive, and the delivery is being verified with
     * the carrier before the warehouse manager corrects it or confirms it (kltn-docs 09 §4.3 step 5).
     */
    DELIVERY_DISPUTED,

    /** Anything else; the note says what. */
    OTHER
}
