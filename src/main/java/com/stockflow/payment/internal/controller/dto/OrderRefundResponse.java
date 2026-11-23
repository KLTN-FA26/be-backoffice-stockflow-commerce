package com.stockflow.payment.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * An order's refund (4B-9, kltn-docs 15 §4.5, §5.3): what its cancellation or closures made refundable, what was
 * recorded refunded, what is left, and the settlements behind it. {@code settlements} and {@code records} are left out
 * (null) in lists. For the customer, the records are the live ones only and the settlements carry no fee reason.
 */
@Schema(name = "OrderRefund")
public record OrderRefundResponse(UUID orderId, @Schema(example = "SO-20261108-000007") String orderNumber,
                                  String currency,
                                  @Schema(description = "15 §5.3: NONE, PENDING (chờ hoàn), PARTIALLY_REFUNDED (hoàn "
                                          + "một phần), REFUNDED (đã hoàn) - computed, never stored",
                                          allowableValues = {"NONE", "PENDING", "PARTIALLY_REFUNDED", "REFUNDED"})
                                  String refundStatus,
                                  BigDecimal refundable, BigDecimal refunded,
                                  @Schema(description = "refundable - refunded, never below zero: what the seller "
                                          + "still owes the customer. The order does not close while it is above "
                                          + "zero (TT-4)")
                                  BigDecimal remaining,
                                  List<Settlement> settlements, List<RefundRecordResponse> records,
                                  @Schema(description = "WF-05 BE-51: what the order's fee items still owe - a "
                                          + "cancellation fee a closure left - shown beside the refund so it is "
                                          + "settled before the money goes out (docs 17 §4.10). Not taken off "
                                          + "remaining; 0 for none")
                                  BigDecimal feeOwed) {

    /**
     * One cancellation or closure: the money it found, the fee and how it was paid.
     *
     * @param feeItemId the cancellation fee item the shortfall became (pay it like an installment); null when none
     */
    public record Settlement(UUID settlementId,
                             @Schema(description = "ORDER_CANCELLED (the order cancelled), REMAINDER_CLOSED (its "
                                     + "rest closed), GOODS_RETURNED (a customer's return refunded), SHIPMENT_RETURNED "
                                     + "(a shipment the carrier brought back, settled at its return receipt, or "
                                     + "lost, WF-05 Q-2)", allowableValues = {"ORDER_CANCELLED", "REMAINDER_CLOSED",
                                     "GOODS_RETURNED", "SHIPMENT_RETURNED"}) String kind,
                             BigDecimal collected, BigDecimal closed, BigDecimal reduced, BigDecimal surplus,
                             BigDecimal fee, BigDecimal feePercent, String feeReason, String feeDetail,
                             BigDecimal feeCovered, BigDecimal refundable, BigDecimal feeOwed, UUID feeItemId,
                             Instant settledAt,
                             @Schema(description = "SHIPMENT_RETURNED: the shipment brought back or lost; null "
                                     + "otherwise") UUID shipmentId,
                             @Schema(description = "The part of closed that is the order's shipping fee waived, "
                                     + "whole dong (WF-05 Q-19). SHIPMENT_RETURNED: the fee in proportion to the "
                                     + "shipment's goods of the order's goods, or all that is left of it when the order "
                                     + "ends with nothing delivered; REMAINDER_CLOSED: what the shipments brought back "
                                     + "left of it, when the closure ends the order with nothing delivered; 0 otherwise")
                             BigDecimal shippingFeeWaived) {
    }
}
