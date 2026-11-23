package com.stockflow.order.internal.domain;

/**
 * What a customer chose for their goods when their return refunded nothing (WF-05 Q-4): the goods were never agreed
 * back, so they are the customer's, held for them in the warehouse until the warehouse manager does as they chose.
 * The values are inventory's {@code CustodyInstruction}, carried to the cartons when Sales record the choice.
 */
public enum ReturnCustodyChoice {
    /** Handed back to the customer, at their cost (the contract's terms). */
    SEND_BACK,
    /** Destroyed on their behalf, with their written consent (biên bản). */
    DESTROY_ON_BEHALF
}
