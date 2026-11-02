package com.stockflow.procurement.internal.domain;

import java.util.Optional;
import java.util.UUID;

/** Port for the {@link SupplierInvoice} aggregate; the adapter maps {@code procurement.supplier_invoices}. */
public interface SupplierInvoiceRepository {

    Optional<SupplierInvoice> findById(UUID invoiceId);

    /**
     * The invoice under a row lock, read after the lock is taken: a match or an edit that waited sees what the one
     * before it committed.
     */
    Optional<SupplierInvoice> findByIdForUpdate(UUID invoiceId);

    SupplierInvoice save(SupplierInvoice invoice);

    /** Whether the supplier already has an invoice numbered so, other than {@code excluding} (BR-INV-001). */
    boolean numberTaken(UUID supplierId, String invoiceNumber, UUID excluding);
}
