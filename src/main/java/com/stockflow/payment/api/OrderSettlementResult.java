package com.stockflow.payment.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * What a cancellation, or a closure of the rest, did with a contract order's money (4B-9, kltn-docs 15 §4.5, §4.6):
 * the surplus paid the fee first ({@code feeCovered}), the rest of it is to be refunded, and what it did not cover is
 * owed on the cancellation fee item.
 *
 * @param kind      {@code ORDER_CANCELLED} or {@code REMAINDER_CLOSED}
 * @param collected what the order's installments had been paid
 * @param reduced   what a closure took off the installments still owing (zero for a cancellation)
 * @param surplus   the money that paid the fee first: all of it collected for a cancellation; for a closure, what was
 *                  paid above the order's new worth
 * @param feeItemId the cancellation fee item; null when nothing is owed
 */
public record OrderSettlementResult(UUID settlementId, String kind, BigDecimal collected, BigDecimal reduced,
                                    BigDecimal surplus, BigDecimal fee, BigDecimal feeCovered, BigDecimal refundable,
                                    BigDecimal feeOwed, UUID feeItemId, String currency) {
}
