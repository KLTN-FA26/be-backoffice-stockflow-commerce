package com.stockflow.procurement.internal.domain;

/**
 * What a three-way match found wrong (SCRUM-309, kltn-docs 04 §4 step 4, {@code ck_supplier_invoice_variances_kind}).
 * Each names what was expected and what was invoiced; the amount is the money at stake, before tax.
 */
public enum InvoiceVarianceKind {
    /**
     * More units billed on a PO line than were received and passed QC, less what MATCHED invoices billed already
     * (kltn-docs 04 BR-02, BR-05): an over-invoice, or an invoice ahead of the goods.
     */
    OVER_INVOICED,
    /** The same on the receipt line the invoice line names: that delivery holds fewer billable units. */
    OVER_INVOICED_RECEIPT,
    /**
     * Billed units that arrived but QC has not concluded on - not inspected yet, or held in quarantine for a second
     * decision. Billable once QC accepts them; matching again then may pass.
     */
    AWAITING_QC,
    /**
     * A SUBCONTRACT order whose settlement is not final (BR-PRD-17): its passed units, and so what may be billed, are
     * known only once the warehouse manager decided on the loss.
     */
    SETTLEMENT_PENDING,
    /** The unit price is off the order's net price (after its discount) by more than the configured tolerance. */
    PRICE,
    /** The tax rate is not the order's. */
    TAX_RATE,
    /**
     * A SUBCONTRACT order billed above its settlement's net payable (passed units, less the approved loss deduction,
     * plus an agreed cancellation fee; kltn-docs 04 BR-07): of the order, not of a line.
     */
    ABOVE_SETTLEMENT
}
