package com.stockflow.payment.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A recorded transfer; {@code unallocatedAmount} is the customer's credit left from it, {@code refundedAmount} what of
 * that credit was asked back. {@code allocations} only where the answer names them (record, detail).
 */
@Schema(name = "CustomerTransfer")
public record CustomerTransferResponse(UUID transferId, UUID customerId, String reference, BigDecimal amount,
                                       BigDecimal unallocatedAmount, BigDecimal refundedAmount, String currency,
                                       LocalDate receivedOn, UUID recordedBy, Instant recordedAt, String note,
                                       @Schema(example = "BANK_TRANSFER",
                                               allowableValues = {"BANK_TRANSFER", "CARRIER_REMITTANCE"})
                                       String channel,
                                       String carrierName,
                                       List<AllocationResponse> allocations) {
}
