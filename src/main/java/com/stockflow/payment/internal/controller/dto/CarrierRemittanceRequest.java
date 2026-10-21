package com.stockflow.payment.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * What a carrier remitted for the balance it collected on one order's delivery (kltn-docs 09 §4.3 step 4, 15 §4.5),
 * read on the bank statement. The reference recorded is {@code remittanceReference:orderNumber}: one remittance
 * carries many orders, each recorded once (15 BR-07).
 */
@Schema(description = "Record a carrier's remittance for a balance it collected on delivery")
public record CarrierRemittanceRequest(
        @Schema(example = "GHN")
        @NotBlank(message = "carrierName is required")
        @Size(max = 120, message = "carrierName is at most 120 characters")
        String carrierName,

        @Schema(example = "GHN-DS-20261021")
        @NotBlank(message = "remittanceReference is required")
        @Size(max = 60, message = "remittanceReference is at most 60 characters")
        @Pattern(regexp = "[\\p{L}\\p{N} ._/#-]+",
                message = "remittanceReference has letters, digits, spaces and . _ / # - only")
        String remittanceReference,

        @NotNull(message = "amount is required")
        @DecimalMin(value = "0.0", inclusive = false, message = "amount must be above 0")
        BigDecimal amount,

        @NotNull(message = "receivedOn is required")
        @PastOrPresent(message = "receivedOn cannot be in the future")
        LocalDate receivedOn,

        @Size(max = 1000, message = "note is at most 1000 characters") String note
) {
}
