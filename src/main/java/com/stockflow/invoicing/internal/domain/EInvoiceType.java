package com.stockflow.invoicing.internal.domain;

/**
 * What an e-invoice is to the invoices before it (plan §1.4; TT 91/2026/TT-BTC Điều 10).
 */
public enum EInvoiceType {

    /** The invoice of a delivered shipment (NĐ 254 Điều 9 khoản 3: every delivery is invoiced). */
    ORIGINAL,
    /**
     * Changes an issued invoice, up or down, and names it; the original stays in force and is flagged once the
     * adjustment is issued. For an error, returned goods or a trade discount ({@link CorrectionBasis}).
     */
    ADJUSTMENT,
    /** Takes an issued invoice's place and names it; the original becomes REPLACED as this one is issued. */
    REPLACEMENT
}
