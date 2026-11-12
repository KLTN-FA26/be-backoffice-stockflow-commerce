package com.stockflow.invoicing.internal.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Port: the lists read off e-invoices - the accountant's work list and the period register. Read models, each one
 * statement; an invoice that is acted on is loaded as the aggregate.
 */
public interface EInvoiceQueries {

    /** A row of the accountant's list. */
    record Listing(UUID id, String code, EInvoiceType type, EInvoiceStatus status, boolean adjusted, UUID orderId,
                   String orderNumber, UUID shipmentId, String shipmentCode, UUID customerId, String buyerName,
                   String buyerTaxCode, LocalDate issueDate, Instant issueDueAt, String templateCode, String symbol,
                   Integer invoiceNumber, BigDecimal subtotal, BigDecimal taxTotal, BigDecimal grandTotal,
                   String warnings, String rejectionCode, Instant issuedAt) {
    }

    /**
     * What to list; every field is optional.
     *
     * @param dueBefore invoices not yet issued whose deadline is before this instant: what is late or about to be
     */
    record Filter(EInvoiceStatus status, EInvoiceType type, UUID orderId, UUID customerId, LocalDate issuedFrom,
                  LocalDate issuedTo, Instant dueBefore) {
    }

    /** A page of invoices: those still to issue first, the nearest deadline first; then the newest. */
    List<Listing> list(Filter filter, int offset, int limit);

    long count(Filter filter);

    /**
     * The adjustments and the replacement of an invoice, voided ones too, oldest first: those that name it, and the
     * trade-discount adjustments that list it.
     */
    List<Listing> correctionsOf(UUID invoiceId);

    /**
     * One issued invoice in the register of a period ("bảng kê hoá đơn"), as the accountant declares it: who it was
     * issued to, and its amount and tax.
     *
     * @param status ISSUED, or REPLACED for one another invoice has since replaced
     */
    record RegisterEntry(String templateCode, String symbol, int invoiceNumber, LocalDate issueDate,
                         EInvoiceType type, EInvoiceStatus status, boolean adjusted, String buyerName,
                         String buyerTaxCode, String buyerAddress, String orderNumber, BigDecimal subtotal,
                         BigDecimal taxTotal, BigDecimal grandTotal, String taxAuthorityCode, String lookupCode,
                         String originalReference) {
    }

    /** Every invoice issued with a date from {@code from} to {@code to}, both included, by symbol and number. */
    List<RegisterEntry> register(LocalDate from, LocalDate to);
}
