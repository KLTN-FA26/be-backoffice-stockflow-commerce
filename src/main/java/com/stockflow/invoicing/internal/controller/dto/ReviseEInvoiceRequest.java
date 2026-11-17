package com.stockflow.invoicing.internal.controller.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;
import java.util.UUID;

/**
 * Corrections to a draft e-invoice. A buyer field left out stays as it is; one sent blank is cleared (and the draft
 * then waits for it before it can be issued). {@code lineNames} renames lines by line id. Money is not here: a draft's
 * quantities and prices are the contract's.
 */
public record ReviseEInvoiceRequest(
        @Size(max = 200) String buyerName,
        @Size(max = 20) String buyerTaxCode,
        @Size(max = 500) String buyerAddress,
        @Size(max = 320) String buyerEmail,
        Map<UUID, @Size(max = 500) String> lineNames,
        @NotNull Long version
) {
}
