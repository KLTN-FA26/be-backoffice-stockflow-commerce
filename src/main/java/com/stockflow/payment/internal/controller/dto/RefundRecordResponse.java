package com.stockflow.payment.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** A refund recorded against an order (4B-9, kltn-docs 15 §4.5 step 16); voided ones are kept with why. */
@Schema(name = "RefundRecord")
public record RefundRecordResponse(UUID refundRecordId, UUID orderId,
                                   @Schema(example = "SO-20261108-000007") String orderNumber, UUID customerId,
                                   BigDecimal amount, String currency, LocalDate refundedOn,
                                   @Schema(example = "FT26313987654") String reference, String note,
                                   @Schema(allowableValues = {"RECORDED", "VOIDED"}) String status,
                                   UUID recordedBy, Instant recordedAt, UUID voidedBy, Instant voidedAt,
                                   String voidReason) {
}
