package com.stockflow.invoicing.internal.controller.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One line of a correction, by the line of the corrected invoice it is about ({@code originalLineId}).
 *
 * <p>On an adjustment {@code quantity} is required and signed - below zero reduces - and {@code unitPrice} is what each
 * unit changes by: left out, the line's own price (a wrong quantity); given, the difference in price (a wrong price).
 * A wrong VAT rate is two lines: the line's whole quantity below zero at the old rate, and above zero with
 * {@code taxRate} the right one.</p>
 *
 * <p>On a replacement every field is what the line should have said; one left out stays as it was and a
 * {@code quantity} of 0 drops the line.</p>
 */
public record CorrectionLineRequest(
        @NotNull UUID originalLineId,
        Integer quantity,
        @DecimalMin("0") BigDecimal unitPrice,
        @DecimalMin("0") BigDecimal taxRate,
        @Size(max = 500) String itemName
) {
}
