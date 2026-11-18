package com.stockflow.invoicing.internal.domain;

/**
 * Why an adjustment or a replacement of an issued invoice exists (WF-05 E4; TT 91/2026/TT-BTC Điều 10, as read in
 * plans/E0-legal.md), and the reduction of an issued invoice whose delivery was corrected to a failure (WF-05 HĐ-3).
 * Each is treated differently, so the invoice says which it is; the same values are
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
    TRADE_DISCOUNT,
    /**
     * The delivery the invoice was drawn up for did not happen: the carrier reported it delivered, the invoice was
     * issued, and the warehouse manager then corrected the delivery to a failure (kltn-docs 09 §4.3 step 5; 21 §4.4
     * "Kết quả giao bị sửa tay Delivered → Delivery Failed sau khi hoá đơn đã phát hành": a full decrease drawn up by
     * the system; WF-05 HĐ-3). Only the system draws it up, reducing every line of the invoice to nothing; the invoice
     * reduced no longer stands for its shipment, which gets a new original when it is delivered again (21 §4.5 step 14,
     * BR-01).
     *
     * <p><b>No agreement is required; one may be attached.</b> Doc 21 asks for the full decrease and is silent on an
     * agreement for it (§4.4, the edge case of §6 and assumption G12 name only "điều chỉnh giảm toàn bộ"). The agreement
     * of khoản 1 điểm b is for an invoice drawn up wrongly - its tax code, quantity, price, rate or amount - and this
     * one was not: it said what the carrier reported, and the delivery is what turned out not to be. It is not a return
     * either (khoản 5 điểm c.1): the goods never reached the buyer. Asking a buyer who has received nothing to sign
     * first would leave an invoice in force for goods nobody has - one the buyer may already have declared for input
     * VAT - for as long as the signature takes. So it is a basis of its own, sent without an agreement, on which the
     * accountant may still attach what evidences the failure (the carrier's confirmation, a minute agreed with the
     * buyer) when the provider or the tax office asks for one. Whether the law reads this case as an error after all is
     * G12's question for the accountant; if it does, {@link #requiresAgreement} is the one line to change.</p>
     */
    DELIVERY_FAILED;

    /** Whether the written agreement of both parties must be on the invoice before it is sent (khoản 1 điểm b). */
    public boolean requiresAgreement() {
        return this == ERROR;
    }

    /**
     * Whether an agreement - or, for a failed delivery, the written evidence of it - may be attached to the draft:
     * required for an error ({@link #requiresAgreement}), optional for {@link #DELIVERY_FAILED}, refused otherwise.
     */
    public boolean acceptsAgreement() {
        return this == ERROR || this == DELIVERY_FAILED;
    }

    /**
     * Whether only the system draws it up: a {@link #DELIVERY_FAILED} reduction follows the correction of a delivery,
     * reduces the whole invoice, and is never drawn up, nor voided, by hand.
     */
    public boolean systemOnly() {
        return this == DELIVERY_FAILED;
    }

    /**
     * Whether every line reduces: a return, a discount and a failed delivery never add to an invoice (khoản 6 điểm c:
     * below zero).
     */
    public boolean decreaseOnly() {
        return this != ERROR;
    }
}
