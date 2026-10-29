package com.stockflow.procurement.api;

import java.time.LocalDate;

/**
 * One SKU's open purchasing, in base units.
 *
 * @param onOrder            still to be received on lines of APPROVED, CONFIRMED and PARTIALLY_RECEIVED orders: bought,
 *                           or about to be sent to the supplier; a short-closed or cancelled line counts nothing, and
 *                           neither does an order the supplier refused ({@code rejectedBySupplier})
 * @param awaitingApproval   ordered on DRAFT and PENDING_APPROVAL orders: not a commitment yet (it may be rejected),
 *                           but a second draft for the same need is the duplicate a planner wants to hear about. A
 *                           line withdrawn from a draft is no longer part of it and counts nothing
 * @param nextExpectedDate   the earliest expected date among the orders behind {@code onOrder}; null when none is open
 *                           or none has a date. It may be in the past on an order approved but not sent yet
 * @param overdue            the part of {@code onOrder} on orders sent to the supplier (CONFIRMED, PARTIALLY_RECEIVED)
 *                           whose expected date is before today (Vietnam time); an APPROVED order is not sent yet.
 *                           Still on order, because a late delivery usually still comes, but somebody should chase it
 *                           before counting on it
 * @param rejectedBySupplier still open on orders the supplier refused: such an order stays CONFIRMED until somebody
 *                           cancels it, yet nothing is received against it, so these units are not on order and the
 *                           need they were bought for is open again
 */
public record SkuOnOrder(String sku, int onOrder, int awaitingApproval, LocalDate nextExpectedDate, int overdue,
                         int rejectedBySupplier) {
}
