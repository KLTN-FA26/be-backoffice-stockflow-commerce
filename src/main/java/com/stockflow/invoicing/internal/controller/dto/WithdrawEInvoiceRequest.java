package com.stockflow.invoicing.internal.controller.dto;

import com.stockflow.common.validation.NotBlankTrimmed;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Why an e-invoice stuck at the provider is taken back - what the accountant checked on the provider's portal - and the
 * version read (HĐ-1). {@code supportReference} (WF-05 BE-07, F-12): the provider's support ticket, or the words of its
 * written answer; required only when the provider says it cannot confirm the result (409
 * {@code EINVOICE_WITHDRAWAL_EVIDENCE_REQUIRED} without it), ignored otherwise.
 */
public record WithdrawEInvoiceRequest(@NotBlankTrimmed @Size(max = 500) String reason, @NotNull Long version,
                                      @Size(max = 200) String supportReference) {
}
