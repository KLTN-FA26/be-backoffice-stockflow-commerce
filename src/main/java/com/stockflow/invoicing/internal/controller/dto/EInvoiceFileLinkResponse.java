package com.stockflow.invoicing.internal.controller.dto;

import java.time.Instant;

/** A short-lived link to an issued e-invoice's XML or PDF. */
public record EInvoiceFileLinkResponse(String url, Instant expiresAt) {
}
