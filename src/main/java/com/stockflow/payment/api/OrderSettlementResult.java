package com.stockflow.payment.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * What a cancellation, or a closure of the rest, did with a contract order's money (4B-9, kltn-docs 15 §4.5, §4.6):
 * the surplus paid the fee first ({@code feeCovered}), the rest of it is to be refunded, and what it did not cover is
 * owed on the cancellation fee item.
 *
 * @param kind      {@code ORDER_CANCELLED}, {@code REMAINDER_CLOSED}, {@code GOODS_RETURNED} (a customer's return) or
 *                  {@code SHIPMENT_RETURNED} (a shipment the carrier brought back or lost, WF-05 Q-2)
 * @param collected what the order's installments had been paid
 * @param reduced   what a closure took off the installments still owing (zero for a cancellation)
 * @param surplus   the money that paid the fee first: all of it collected for a cancellation; for a closure, what was
 *                  paid above the order's new worth
 * @param feeItemId the cancellation fee item; null when nothing is owed
 * @param shippingFeeWaived the part of the order's shipping fee, with its VAT, waived by this settlement (WF-05 Q-19) -
 *                  part of what it closed: {@code SHIPMENT_RETURNED}, the shipment's share, or all that is left of the
 *                  fee when the order ends with nothing delivered; {@code REMAINDER_CLOSED}, what the shipments brought
 *                  back left of the fee when the closure ends the order with nothing delivered (the Q-19 edge, owner
 *                  2026-11-23), zero otherwise. Payment works it out; the order keeps it from here - on the shipment,
 *                  or on the closure - so that invoicing, which cannot read payment, invoices the fee less the sum of
 *                  what was waived from the one figure rather than a second copy of the rule. Zero for the other kinds
 * @param feeBearer who bears the fee entered (D46, D50); null when none was
 * @param feeBorneByCompany what the company bears when {@code feeBearer} is COMPANY - the cost for the accountant,
 *                  "Phí do công ty chịu"; zero otherwise. {@code fee} is what the customer is charged and is zero then
 */
public record OrderSettlementResult(UUID settlementId, String kind, BigDecimal collected, BigDecimal reduced,
                                    BigDecimal surplus, BigDecimal fee, BigDecimal feeCovered, BigDecimal refundable,
                                    BigDecimal feeOwed, UUID feeItemId, String currency, BigDecimal shippingFeeWaived,
                                    FeeBearer feeBearer, BigDecimal feeBorneByCompany) {
}
