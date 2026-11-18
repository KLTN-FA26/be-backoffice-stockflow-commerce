package com.stockflow.invoicing.internal.controller.dto;

import com.stockflow.common.validation.NotBlankTrimmed;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Why an e-invoice stuck at the provider is taken back - what the accountant checked on the provider's portal - and the
 * version read (HĐ-1).
 */
public record WithdrawEInvoiceRequest(@NotBlankTrimmed @Size(max = 500) String reason, @NotNull Long version) {
}
