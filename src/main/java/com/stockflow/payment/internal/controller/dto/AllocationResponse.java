package com.stockflow.payment.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Money of one transfer applied to one receivable. */
@Schema(name = "TransferAllocation")
public record AllocationResponse(UUID allocationId, UUID transferId, UUID receivableId, BigDecimal amount,
                                 Instant allocatedAt, UUID allocatedBy) {
}
