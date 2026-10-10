package com.stockflow.payment.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A transfer the customer sent, as read on the bank statement (kltn-docs 15 BR-04). It pays the
 * receivables listed in {@code allocateTo}, or the earliest due first (BR-09).
 */
@Schema(description = "Record a customer transfer paying what they owe")
public record RecordTransferRequest(
        @NotNull(message = "customerId is required") UUID customerId,

        @Schema(description = "The bank statement reference; recorded once (BR-07)", example = "FT26283123456")
        @NotBlank(message = "reference is required")
        @Size(max = 100, message = "reference is at most 100 characters")
        @Pattern(regexp = "[\\p{L}\\p{N} ._/:#-]+", message = "reference has letters, digits, spaces and . _ / : # - only")
        String reference,

        @NotNull(message = "amount is required")
        @DecimalMin(value = "0.0", inclusive = false, message = "amount must be above 0")
        BigDecimal amount,

        @Schema(example = "VND")
        @Pattern(regexp = "[A-Z]{3}", message = "currency is a 3-letter ISO code")
        String currency,

        @NotNull(message = "receivedOn is required")
        @PastOrPresent(message = "receivedOn cannot be in the future")
        LocalDate receivedOn,

        @Schema(description = "Receivables the customer named; omit for earliest due first")
        @Size(max = 100, message = "at most 100 receivables")
        List<UUID> allocateTo,

        @Size(max = 1000, message = "note is at most 1000 characters") String note
) {
}
