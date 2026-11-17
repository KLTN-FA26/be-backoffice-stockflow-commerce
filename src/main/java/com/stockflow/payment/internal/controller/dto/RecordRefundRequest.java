package com.stockflow.payment.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A refund the accountant made outside the system and records (4B-9, kltn-docs 15 §4.5 step 16): how much, the day it
 * left, the outgoing statement line. At most what the order still has to refund (BR-14).
 */
@Schema(description = "Record a refund made to the customer")
public record RecordRefundRequest(
        @NotNull(message = "amount is required")
        @DecimalMin(value = "1", message = "amount is at least 1")
        @Digits(integer = 15, fraction = 0, message = "amount is a whole number of dong")
        BigDecimal amount,
        @Schema(description = "The day the money left; not after today in Vietnam (checked by the service)")
        @NotNull(message = "refundedOn is required")
        LocalDate refundedOn,
        @Schema(description = "The outgoing bank statement reference; one live refund record each",
                example = "FT26313987654")
        @NotBlank(message = "reference is required")
        @Size(max = 100, message = "reference is at most 100 characters")
        @Pattern(regexp = "[\\p{L}\\p{N} ._/:#-]+", message = "reference has letters, digits, spaces and . _ / : # - only")
        String reference,
        @Size(max = 500, message = "note is at most 500 characters") String note
) {
}
