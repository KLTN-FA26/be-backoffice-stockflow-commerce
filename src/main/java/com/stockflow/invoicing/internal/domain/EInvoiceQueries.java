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
     * A page of the invoices issued to one customer, the newest invoice date first: what the customer sees on the
     * portal. Issued ones only - ISSUED, and REPLACED so the customer still finds the invoice a replacement names; a
     * draft, a refused or a voided invoice is the seller's working paper and never the customer's.
     */
    List<Listing> issuedTo(UUID customerId, int offset, int limit);

    long countIssuedTo(UUID customerId);

    /**
     * The one invoice issued to {@code customerId}; empty for a draft, for another customer's invoice and for no
     * invoice alike - the caller cannot tell which, and neither can whoever asked.
     */
    java.util.Optional<Listing> findIssuedTo(UUID customerId, UUID id);

    /**
     * Those of {@code shipmentIds} that have an invoice in force: ISSUED, and not an adjustment - an original, or the
     * invoice that replaced it. What an order's completion asks about its delivered shipments.
     */
    java.util.Set<UUID> invoicedAmong(java.util.Collection<UUID> shipmentIds);

    /**
     * Whether the order has an invoice of its shipping fee rest that is not issued yet: drawn up and not voided, and
     * still a draft, at the provider, or refused.
     */
    boolean shippingFeeInvoiceUnissued(UUID orderId);

    /**
     * One issued invoice in the register of a period ("bảng kê hoá đơn"), as the accountant declares it: who it was
     * issued to, and its amount and tax - in total and <b>by VAT rate</b>. The VAT return asks for the sales and the
     * tax of each rate apart (0%, 5%, 10%, and the reduced 8%), and one invoice may carry lines at two rates; a
     * register with one amount per invoice could not be declared from without opening every invoice.
     *
     * @param status ISSUED, or REPLACED for one another invoice has since replaced
     * @param byRate the invoice's amount and tax at each rate of its lines, lowest rate first (signed, as the lines
     *               are: an adjustment that reduces is below zero); empty for an invoice of note lines only
     */
    record RegisterEntry(String templateCode, String symbol, int invoiceNumber, LocalDate issueDate,
                         EInvoiceType type, EInvoiceStatus status, boolean adjusted, String buyerName,
                         String buyerTaxCode, String buyerAddress, String orderNumber, BigDecimal subtotal,
                         BigDecimal taxTotal, BigDecimal grandTotal, String taxAuthorityCode, String lookupCode,
                         String originalReference, List<TaxRateTotal> byRate) {

        public RegisterEntry {
            byRate = byRate == null ? List.of() : List.copyOf(byRate);
        }

        /** An entry whose amounts by rate are not known; they read as none. */
        public RegisterEntry(String templateCode, String symbol, int invoiceNumber, LocalDate issueDate,
                             EInvoiceType type, EInvoiceStatus status, boolean adjusted, String buyerName,
                             String buyerTaxCode, String buyerAddress, String orderNumber, BigDecimal subtotal,
                             BigDecimal taxTotal, BigDecimal grandTotal, String taxAuthorityCode, String lookupCode,
                             String originalReference) {
            this(templateCode, symbol, invoiceNumber, issueDate, type, status, adjusted, buyerName, buyerTaxCode,
                    buyerAddress, orderNumber, subtotal, taxTotal, grandTotal, taxAuthorityCode, lookupCode,
                    originalReference, List.of());
        }
    }

    /** Every invoice issued with a date from {@code from} to {@code to}, both included, by symbol and number. */
    List<RegisterEntry> register(LocalDate from, LocalDate to);
}
