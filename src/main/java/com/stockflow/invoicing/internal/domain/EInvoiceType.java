package com.stockflow.invoicing.internal.domain;

/**
 * What an e-invoice is to the invoices before it (plan §1.4; TT 91/2026/TT-BTC Điều 10).
 */
public enum EInvoiceType {

    /** The invoice of a delivered shipment (NĐ 254 Điều 9 khoản 3: every delivery is invoiced). */
    ORIGINAL,
    /**
     * Changes an issued invoice, up or down, and names it; the original stays in force. Package E4 draws these up -
     * here the type exists so the schema and the aggregate have its place.
     */
    ADJUSTMENT,
    /** Takes an issued invoice's place and names it; the original becomes REPLACED. Package E4. */
    REPLACEMENT
}
