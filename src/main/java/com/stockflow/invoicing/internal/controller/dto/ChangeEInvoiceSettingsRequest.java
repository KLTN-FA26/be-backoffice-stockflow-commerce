package com.stockflow.invoicing.internal.controller.dto;

import jakarta.validation.constraints.NotNull;

/** Whether complete drafts are issued automatically, and the version of the settings read. */
public record ChangeEInvoiceSettingsRequest(@NotNull Boolean autoIssue, @NotNull Long version) {
}
