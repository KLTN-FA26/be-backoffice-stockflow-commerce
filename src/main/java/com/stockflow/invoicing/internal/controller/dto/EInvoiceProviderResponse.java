package com.stockflow.invoicing.internal.controller.dto;

/**
 * An e-invoice provider as configured. The account's password is never returned - only whether one is stored
 * (kltn-docs 21 BR-10).
 *
 * @param environment {@code SANDBOX} or {@code PRODUCTION}
 * @param active      whether invoices are issued through it now
 */
public record EInvoiceProviderResponse(String provider, String environment, String username, boolean secretSet,
                                       boolean active, boolean autoIssue, long version) {
}
