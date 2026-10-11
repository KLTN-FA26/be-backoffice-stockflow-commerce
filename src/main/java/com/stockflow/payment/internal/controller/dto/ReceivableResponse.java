package com.stockflow.payment.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** What a customer owes for one delivered credit order; {@code allocations} only on the detail. */
@Schema(name = "Receivable")
public record ReceivableResponse(UUID receivableId, UUID orderId, UUID customerId, BigDecimal amount,
                                 BigDecimal paidAmount, BigDecimal outstanding, String currency, Instant issuedAt,
                                 LocalDate dueDate, @Schema(example = "OPEN") String status, Instant settledAt,
                                 List<AllocationResponse> allocations) {
}
