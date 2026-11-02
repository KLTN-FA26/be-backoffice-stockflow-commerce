package com.stockflow.procurement.internal.repository;

import com.stockflow.procurement.internal.domain.SupplierInvoiceStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Read side of supplier invoices for the list screen. Rows carry no lines. */
public interface SupplierInvoiceSearch {

    /**
     * Every field null means any. {@code purchaseOrderId} keeps the invoices of that order, and the invoices over
     * several orders with a line on it; {@code number} is part of the supplier's invoice number.
     */
    record Criteria(List<SupplierInvoiceStatus> statuses, UUID supplierId, UUID purchaseOrderId, String number,
                    LocalDate invoicedFrom, LocalDate invoicedTo) {
    }

    record Row(UUID id, String invoiceNumber, UUID supplierId, UUID purchaseOrderId, SupplierInvoiceStatus status,
               LocalDate invoiceDate, LocalDate dueDate, String currency, BigDecimal totalAmount) {
    }

    Page<Row> search(Criteria criteria, Pageable pageable);

    /** One query for a page: supplier id to name. */
    Map<UUID, String> supplierNames(Collection<UUID> supplierIds);

    /** One query for a page: invoice id to how many variances its last match found. */
    Map<UUID, Integer> varianceCounts(Collection<UUID> invoiceIds);
}
