package com.stockflow.order.api;

/**
 * Why the carrier could not deliver (kltn-docs 09 §4.3 step 2 and edge cases): the customer was absent, refused it,
 * the address was wrong, the parcel was lost or arrived damaged, or something else the note explains.
 */
public enum DeliveryFailureReason {
    CUSTOMER_ABSENT,
    REFUSED,
    WRONG_ADDRESS,
    LOST,
    DAMAGED,
    OTHER
}
