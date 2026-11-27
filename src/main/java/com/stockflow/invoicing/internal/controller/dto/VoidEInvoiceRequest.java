package com.stockflow.invoicing.internal.controller.dto;

import com.stockflow.common.validation.NotBlankTrimmed;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Why a draft e-invoice is given up, and the version read. {@code reasonCode} (WF-05 BE-10, F-14):
 * {@code UNSENDABLE_REVERSAL} to void the reduction of a failed delivery that no provider can send - otherwise never
 * voided - before it is made on the provider's portal and recorded; absent on every other draft.
 */
public record VoidEInvoiceRequest(@NotBlankTrimmed @Size(max = 500) String reason, @NotNull Long version,
                                  @Pattern(regexp = "UNSENDABLE_REVERSAL") String reasonCode) {
}
