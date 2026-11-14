package com.stockflow.invoicing.internal.controller.dto;

import com.stockflow.common.validation.NotBlankTrimmed;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * The replacement of an issued e-invoice (WF-05 E4; TT 91/2026/TT-BTC Điều 10 khoản 1 điểm b.2): the invoice as it
 * should have been. Lines not named stay as they were; {@code buyer} puts the buyer right.
 */
public record ReplaceEInvoiceRequest(
        @NotBlankTrimmed @Size(max = 500) String reason,
        @Valid CorrectionBuyerRequest buyer,
        @Size(max = 200) List<@Valid @NotNull CorrectionLineRequest> lines
) {
}
