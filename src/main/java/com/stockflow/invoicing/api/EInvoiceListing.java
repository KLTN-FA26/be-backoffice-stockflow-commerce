package com.stockflow.invoicing.api;

import com.stockflow.common.api.PageResponse;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * The accountant's list of VAT e-invoices ({@code GET /api/v1/einvoices}, WF-05 E2, kltn-docs 21) for another module
 * to show: the back-office assistant (ADR-0011) is asked "hoá đơn nào còn chưa phát hành?", "đơn SO-… đã xuất hoá đơn
 * chưa?". The screen filters by the order's id, which nobody outside it can read; this filters by its number, which
 * every invoice carries as printed.
 *
 * <p><b>Same order as the screen</b>: what is still to issue first, the nearest deadline first, then the newest. Same
 * notion of overdue: not issued, not voided, and its deadline (the working day after delivery, NĐ 254) reached.</p>
 *
 * <p><b>Scope.</b> The screen's: every customer's invoice, from the back office, under {@code einvoices:READ} with
 * scope ALL. A portal account reads only its own issued invoices, elsewhere ({@code CustomerEInvoiceController}); the
 * caller refuses it, as the screen does. Read only: nothing is drawn up, issued or voided here.</p>
 */
public interface EInvoiceListing {

    /** The most invoices one call returns. */
    int MAX_INVOICES = 50;

    /**
     * Every status ({@code ck_einvoice_status}, 21 §5.1): drawn up and not sent, sent and waiting for the provider's
     * answer, issued (numbered and signed: never changes), refused by the provider or the tax authority, a draft
     * given up, an issued invoice replaced by another.
     */
    List<String> STATUSES = List.of("DRAFT", "SUBMITTING", "ISSUED", "REJECTED", "VOIDED_DRAFT", "REPLACED");

    /** Still to issue: the accountant's work. */
    List<String> TO_ISSUE_STATUSES = List.of("DRAFT", "SUBMITTING", "REJECTED");

    /**
     * What to list; every field is optional.
     *
     * @param statuses    any of {@link #STATUSES}; empty for every status
     * @param orderNumber the order's number exactly, case aside
     * @param customerId  the buyer
     * @param issuedFrom  first invoice date, the day of delivery; {@code issuedTo} the last, both included
     * @param overdueAt   only invoices still to issue whose deadline is reached at this instant; null for no such
     *                    filter
     */
    record Filter(Collection<String> statuses, String orderNumber, UUID customerId, LocalDate issuedFrom,
                  LocalDate issuedTo, Instant overdueAt) {

        public Filter {
            statuses = statuses == null ? List.of() : List.copyOf(statuses);
        }
    }

    /**
     * The invoices that match, in the screen's order.
     *
     * @param now   the instant each row's {@code overdue} is judged at
     * @param limit 1..{@value #MAX_INVOICES}
     * @throws com.stockflow.common.error.BusinessException {@code VALIDATION_FAILED} for a status not in
     *         {@link #STATUSES}, {@code issuedTo} before {@code issuedFrom}, or a limit out of range
     */
    PageResponse<EInvoiceRow> einvoices(Filter filter, Instant now, int limit);

    /**
     * One invoice as the list shows it.
     *
     * @param code           the invoice's own code in this system
     * @param type           ORIGINAL (a delivered shipment's), ADJUSTMENT or REPLACEMENT (correcting an issued one)
     * @param adjusted       an adjustment of it was issued; it stays in force
     * @param shipmentCode   the delivered shipment it invoices; null for an invoice of a fee or a correction
     * @param contractNumber the order's sales contract; null for an order without one
     * @param buyerName      the buyer as the invoice names it
     * @param issueDate      the invoice's date: the day the goods were delivered
     * @param issueDueAt     the deadline to issue it
     * @param overdue        not issued, not voided, and the deadline reached
     * @param templateCode   with {@code symbol} and {@code invoiceNumber}, what the provider gave it on issue; null
     *                       before
     * @param rejectionCode  why the provider or the tax authority refused it; null unless REJECTED
     */
    record EInvoiceRow(String code, String type, String status, boolean adjusted, String orderNumber,
                       String shipmentCode, String contractNumber, UUID customerId, String buyerName,
                       LocalDate issueDate, Instant issueDueAt, boolean overdue, String templateCode, String symbol,
                       Integer invoiceNumber, String currency, BigDecimal subtotal, BigDecimal taxTotal,
                       BigDecimal grandTotal, String rejectionCode, Instant issuedAt) {
    }
}
