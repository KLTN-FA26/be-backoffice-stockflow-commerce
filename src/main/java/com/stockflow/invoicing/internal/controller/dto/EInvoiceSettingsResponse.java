package com.stockflow.invoicing.internal.controller.dto;

import java.util.List;

/**
 * The e-invoice provider in use and its series. The account's secret is never returned - only whether one is stored.
 */
public record EInvoiceSettingsResponse(String provider, String environment, String endpoint, String username,
                                       boolean secretSet, boolean autoIssue, List<Series> series, long version) {

    public record Series(String templateCode, String symbol, int year) {
    }
}
