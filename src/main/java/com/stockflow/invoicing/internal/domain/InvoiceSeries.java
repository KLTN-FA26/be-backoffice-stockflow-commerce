package com.stockflow.invoicing.internal.domain;

/**
 * The template code and symbol invoices are numbered under at a provider (TT 91/2026/TT-BTC Điều 4, Phụ lục I): the
 * template is one digit ({@code 1}: a VAT invoice); the symbol is six characters - {@code C} with the tax authority's
 * code or {@code K} without, the last two digits of the year, the kind of invoice ({@code T}: registered by a
 * business), and two letters of the seller's own. So a symbol belongs to one year, and the provider numbers from 1
 * again under next year's (NĐ 254 Phụ lục điểm 2).
 *
 * <p>The template is always {@link #VAT_TEMPLATE}: the seller declares VAT by the credit method and issues VAT invoices
 * only, so any other digit is a typing error that would reach the provider as another kind of invoice. A series under
 * another one is refused when it is entered and when an invoice would be sent under it; the database says the same
 * ({@code ck_einvoice_series_template}, {@code ck_einvoice_template}).</p>
 */
public record InvoiceSeries(String provider, String templateCode, String symbol, int year) {

    /** Template 1: a VAT invoice (hoá đơn giá trị gia tăng), the only kind the seller issues. */
    public static final String VAT_TEMPLATE = "1";

    /** Whether invoices may be numbered under it: a VAT invoice's template. */
    public boolean vatTemplate() {
        return VAT_TEMPLATE.equals(templateCode);
    }
}
