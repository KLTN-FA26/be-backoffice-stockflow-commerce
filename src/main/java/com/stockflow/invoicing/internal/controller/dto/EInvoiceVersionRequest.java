package com.stockflow.invoicing.internal.controller.dto;

import jakarta.validation.constraints.NotNull;

/** The version of the e-invoice the caller read: an action on one that changed meanwhile is refused. */
public record EInvoiceVersionRequest(@NotNull Long version) {
}
