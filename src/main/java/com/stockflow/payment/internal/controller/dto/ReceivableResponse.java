package com.stockflow.payment.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * What is owed for one delivered credit order, a cancellation fee, or (G2-3) a deposit balance the carrier collected on
 * delivery ({@code collector=CARRIER}: the carrier's to remit, not the customer's debt); {@code allocations} only on
 * the detail.
 */
@Schema(name = "Receivable")
public record ReceivableResponse(UUID receivableId, UUID orderId,
                                 @Schema(example = "SO-20261021-000123",
                                         description = "SCRUM-193: the number of the order it is for; null when not known")
                                 String orderNumber, UUID customerId,
                                 @Schema(example = "CREDIT_ORDER",
                                         allowableValues = {"CREDIT_ORDER", "CANCELLATION_FEE", "DEPOSIT_BALANCE"})
                                 String kind,
                                 @Schema(example = "CUSTOMER", allowableValues = {"CUSTOMER", "CARRIER"})
                                 String collector,
                                 UUID shipmentId, BigDecimal amount, BigDecimal paidAmount, BigDecimal outstanding,
                                 String currency, Instant issuedAt, LocalDate dueDate,
                                 @Schema(example = "OPEN") String status, Instant settledAt,
                                 List<AllocationResponse> allocations) {
}
