package com.stockflow.payment.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** A recorded customer transfer; {@code unallocatedAmount} is the customer's credit left from it. */
@Schema(name = "CustomerTransfer")
public record CustomerTransferResponse(UUID transferId, UUID customerId, String reference, BigDecimal amount,
                                       BigDecimal unallocatedAmount, String currency, LocalDate receivedOn,
                                       UUID recordedBy, Instant recordedAt, String note,
                                       List<AllocationResponse> allocations) {
}
