package com.stockflow.payment.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A payment receipt the accountant records from the bank statement (4B-6, kltn-docs 15 §4.2 step 6): one statement
 * line, the installments it pays and how much of each. The receipt is the sum of its lines.
 */
@Schema(description = "Record a payment receipt against installments")
public record RecordReceiptRequest(
        @NotNull(message = "customerId is required") UUID customerId,
        @Schema(description = "The bank statement reference; one live receipt each (BR-03)", example = "FT26313123456")
        @NotBlank(message = "reference is required")
        @Size(max = 100, message = "reference is at most 100 characters")
        @Pattern(regexp = "[\\p{L}\\p{N} ._/:#-]+", message = "reference has letters, digits, spaces and . _ / : # - only")
        String reference,
        @Schema(description = "Not after today in Vietnam (checked by the service: the server runs in UTC)")
        @NotNull(message = "receivedOn is required")
        LocalDate receivedOn,
        @Size(max = 500, message = "note is at most 500 characters") String note,
        @NotEmpty(message = "a receipt pays at least one installment")
        @Size(max = 100, message = "at most 100 lines")
        List<@Valid @NotNull(message = "a line is required") Line> lines
) {

    /** One installment and the money paid to it. */
    public record Line(
            @NotNull(message = "installmentId is required") UUID installmentId,
            @NotNull(message = "amount is required")
            @DecimalMin(value = "1", message = "amount is at least 1")
            @Digits(integer = 15, fraction = 0, message = "amount is a whole number of dong")
            BigDecimal amount
    ) {
    }
}
