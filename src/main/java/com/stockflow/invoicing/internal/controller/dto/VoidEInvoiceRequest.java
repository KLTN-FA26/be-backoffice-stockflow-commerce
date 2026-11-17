package com.stockflow.invoicing.internal.controller.dto;

import com.stockflow.common.validation.NotBlankTrimmed;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Why a draft e-invoice is given up, and the version read. */
public record VoidEInvoiceRequest(@NotBlankTrimmed @Size(max = 500) String reason, @NotNull Long version) {
}
