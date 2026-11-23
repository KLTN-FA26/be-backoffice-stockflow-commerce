package com.stockflow.invoicing.internal.controller.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * An issued e-invoice to be e-mailed to its buyer again (WF-05 audit r2 M-1): accepted, and sent a moment later by the
 * mail delivery, which retries it like the first mail.
 *
 * @param recipient   the customer's invoice e-mail it goes to, as things stand now - shown so whoever asked sees where
 * @param requestedAt when it was asked for
 */
public record EInvoiceMailResendResponse(UUID einvoiceId, String recipient, Instant requestedAt) {
}
