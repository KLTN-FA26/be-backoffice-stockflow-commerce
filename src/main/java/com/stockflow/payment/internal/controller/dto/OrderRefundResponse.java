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
                                  BigDecimal refundable, BigDecimal refunded, BigDecimal remaining,
                                  List<Settlement> settlements, List<RefundRecordResponse> records) {

    /**
     * One cancellation or closure: the money it found, the fee and how it was paid.
     *
     * @param feeItemId the cancellation fee item the shortfall became (pay it like an installment); null when none
     */
    public record Settlement(UUID settlementId,
                             @Schema(allowableValues = {"ORDER_CANCELLED", "REMAINDER_CLOSED"}) String kind,
                             BigDecimal collected, BigDecimal closed, BigDecimal reduced, BigDecimal surplus,
                             BigDecimal fee, BigDecimal feePercent, String feeReason, String feeDetail,
                             BigDecimal feeCovered, BigDecimal refundable, BigDecimal feeOwed, UUID feeItemId,
                             Instant settledAt) {
    }
}
