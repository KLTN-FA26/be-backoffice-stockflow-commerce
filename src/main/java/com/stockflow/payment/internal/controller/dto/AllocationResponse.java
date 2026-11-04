package com.stockflow.payment.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Money of one transfer applied to an order (G2-3: {@code target=ORDER}, prepaid, deposit or balance) or to one of its
 * receivables ({@code target=RECEIVABLE}). A reversal is a row of its own (kltn-docs 15 BR-06): a negative amount,
 * the allocation it reverses and the accountant's reason in {@code note}.
 */
@Schema(name = "TransferAllocation")
public record AllocationResponse(UUID allocationId, UUID transferId, UUID orderId,
                                 @Schema(example = "ORDER", allowableValues = {"ORDER", "RECEIVABLE"}) String target,
                                 UUID receivableId, BigDecimal amount, Instant allocatedAt, UUID allocatedBy,
                                 UUID reversesAllocationId, String note) {
}
