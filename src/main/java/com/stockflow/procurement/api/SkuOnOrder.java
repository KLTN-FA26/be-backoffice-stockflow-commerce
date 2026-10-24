package com.stockflow.procurement.api;

import java.time.LocalDate;

/**
 * One SKU's open purchasing, in base units.
 *
 * @param onOrder           still to be received on lines of APPROVED, CONFIRMED and PARTIALLY_RECEIVED orders: bought,
 *                          or about to be sent to the supplier; a short-closed or cancelled line counts nothing
 * @param awaitingApproval  ordered on DRAFT and PENDING_APPROVAL orders: not a commitment yet (it may be rejected), but
 *                          a second draft for the same need is the duplicate a planner wants to hear about
 * @param nextExpectedDate  the earliest expected date among the orders behind {@code onOrder}; null when none is open
 *                          or none has a date
 */
public record SkuOnOrder(String sku, int onOrder, int awaitingApproval, LocalDate nextExpectedDate) {
}
