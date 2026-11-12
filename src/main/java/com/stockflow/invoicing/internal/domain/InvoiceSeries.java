package com.stockflow.invoicing.internal.domain;

/**
 * The template code and symbol invoices are numbered under at a provider (TT 91/2026/TT-BTC Điều 4, Phụ lục I): the
 * template is one digit ({@code 1}: a VAT invoice); the symbol is six characters - {@code C} with the tax authority's
 * code or {@code K} without, the last two digits of the year, the kind of invoice ({@code T}: registered by a
 * business), and two letters of the seller's own. So a symbol belongs to one year, and the provider numbers from 1
 * again under next year's (NĐ 254 Phụ lục điểm 2).
 */
public record InvoiceSeries(String provider, String templateCode, String symbol, int year) {
}
