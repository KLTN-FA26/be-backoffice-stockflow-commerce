package com.stockflow.procurement.internal.domain;

/**
 * Where a supplier invoice stands (docs 03 §4, kltn-docs 04 §5, {@code ck_supplier_invoices_status}).
 *
 * <p>RECEIVED is the entered invoice not matched yet - the documents' "Draft". A match makes it MATCHED or EXCEPTION
 * (SCRUM-309); an edit of either takes it back to RECEIVED. DISPUTED and PAID belong to resolving variances and
 * recording the payment (SCRUM-310), which start from EXCEPTION and MATCHED; nothing here writes them.</p>
 */
public enum SupplierInvoiceStatus {
    RECEIVED, MATCHED, EXCEPTION, DISPUTED, VOID, PAID;

    /**
     * Whether the accountant may still change, match or void the invoice: before a match, or after one that found
     * variances. A MATCHED invoice has consumed the receipts it billed and is the resolution story's to move on.
     */
    public boolean isOpen() {
        return this == RECEIVED || this == EXCEPTION;
    }
}
