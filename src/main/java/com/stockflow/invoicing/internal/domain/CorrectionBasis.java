package com.stockflow.invoicing.internal.domain;

/**
 * Why an adjustment or a replacement of an issued invoice exists (WF-05 E4; TT 91/2026/TT-BTC Điều 10, as read in
 * plans/E0-legal.md). The law treats the three differently, so the invoice says which it is; the same values are
 * {@code ck_einvoice_correction_basis}.
 */
public enum CorrectionBasis {

    /**
     * The invoice was drawn up wrongly - the buyer's tax code, a quantity, a price, a rate, an amount (khoản 1 điểm b).
     * The seller chooses to adjust or to replace, and with a business buyer the two put the error in a written
     * agreement first: no such correction is sent without it.
     */
    ERROR,
    /**
     * The buyer returned goods after the invoice (khoản 5 điểm c.1): the seller draws up an adjustment that reduces.
     * No agreement - the return's own record is the evidence, and the adjustment names it.
     */
    RETURN,
    /**
     * A trade discount earned by volume or revenue, given after the invoices it is earned on (khoản 5 điểm b): an
     * adjustment that reduces, with the list of the invoices it covers. No agreement. A price reduction that is not
     * tied to volume or revenue has no rule of its own in the text read (E0 row 5, not verified) and is not offered.
     */
    TRADE_DISCOUNT;

    /** Whether the written agreement of both parties must be on the invoice before it is sent (khoản 1 điểm b). */
    public boolean requiresAgreement() {
        return this == ERROR;
    }

    /** Whether every line reduces: a return and a discount never add to an invoice (khoản 6 điểm c: below zero). */
    public boolean decreaseOnly() {
        return this != ERROR;
    }
}
