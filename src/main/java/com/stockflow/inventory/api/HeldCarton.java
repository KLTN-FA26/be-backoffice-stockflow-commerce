package com.stockflow.inventory.api;

import java.time.Instant;

/**
 * A carton of a return held for the customer, or whose custody ended (WF-05 Q-4): the return's detail shows them so
 * Sales see what the customer's choice is about, and record it only when one is there.
 *
 * @param ownership CUSTOMER_HELD, CUSTOMER_SENT_BACK or CUSTOMER_DESTROYED
 * @param choice    what the customer chose as Sales recorded it (SEND_BACK, DESTROY_ON_BEHALF); null until they did
 * @param endedAt   when the warehouse manager carried it out; null while it is held
 */
public record HeldCarton(String lpn, String sku, int quantity, String locationCode, String ownership, String choice,
                         Instant heldAt, Instant endedAt) {
}
