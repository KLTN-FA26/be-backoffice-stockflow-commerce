package com.stockflow.invoicing.internal.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Invoices for the tests of this package and of the services: a delivered shipment of 20 cartons and a plate fee. */
public final class EInvoiceFixtures {

    public static final Instant NOW = Instant.parse("2026-11-12T03:00:00Z");
    public static final LocalDate DELIVERED_ON = LocalDate.of(2026, 11, 12);
    public static final InvoiceParty SELLER = new InvoiceParty("CONG TY TNHH BAO BI STOCKFLOW", "0312345678",
            "1 Le Loi, P. Ben Nghe, TP HCM", null);
    public static final InvoiceParty BUYER = new InvoiceParty("Cong ty Tra Sua", "0398765432",
            "12 Nguyen Hue, P. Ben Nghe, TP HCM", "ke.toan@trasua.example.com");
    public static final InvoiceSeries SERIES = new InvoiceSeries("SANDBOX", "1", "C26TSB", 2026);

    private EInvoiceFixtures() {
    }

    public static List<EInvoiceLine> lines() {
        return List.of(
                EInvoiceLine.goods(UUID.randomUUID(), 1, UUID.randomUUID(), "CUP12-0398765432-01", "Ly giay 12oz",
                        "Thung", 20, 50, new BigDecimal("100000"), new BigDecimal("10")),
                EInvoiceLine.fee(UUID.randomUUID(), 2, EInvoiceLine.FeeKind.PLATE_FEE, "CUP12-0398765432-01",
                        "Phi khuon in", "Lan", new BigDecimal("500000"), new BigDecimal("8")));
    }

    public static EInvoice.Delivery delivery() {
        return new EInvoice.Delivery(UUID.randomUUID(), "SO-20261112-0001", "HD-20261112-0001", UUID.randomUUID(),
                "SO-20261112-0001-S1", null, UUID.randomUUID(), DELIVERED_ON);
    }

    public static EInvoice draft() {
        return draft(BUYER);
    }

    public static EInvoice draft(InvoiceParty buyer) {
        return EInvoice.drawUpOriginal(UUID.randomUUID(), "EINV-20261112-0001", delivery(), SELLER, buyer, lines(),
                false, false);
    }

    /** An invoice brought to {@code status} the way the application brings it there. */
    public static EInvoice in(EInvoiceStatus status) {
        EInvoice invoice = draft();
        switch (status) {
            case DRAFT -> { }
            case SUBMITTING -> invoice.submit(SERIES, null, NOW);
            case ISSUED -> issue(invoice);
            case REJECTED -> {
                invoice.submit(SERIES, null, NOW);
                invoice.rejected("INVALID_BUYER_TAX_CODE", "refused");
            }
            case VOIDED_DRAFT -> invoice.voidDraft("drawn up by mistake", null, NOW);
            case REPLACED -> {
                // Package E4 moves an invoice here; until then it only ever comes from the database.
                issue(invoice);
                invoice = EInvoice.restore(withStatus(invoice.snapshot(), EInvoiceStatus.REPLACED));
            }
        }
        return invoice;
    }

    private static void issue(EInvoice invoice) {
        invoice.submit(SERIES, null, NOW);
        invoice.issued(1, "SBX0001", "SBXLOOKUP", NOW, "einvoices/2026/11/12/a.xml", "einvoices/2026/11/12/a.pdf",
                NOW);
    }

    public static EInvoice.Snapshot withStatus(EInvoice.Snapshot s, EInvoiceStatus status) {
        return new EInvoice.Snapshot(s.id(), s.code(), s.type(), status, s.adjusted(), s.orderId(), s.orderNumber(),
                s.contractNumber(), s.shipmentId(), s.shipmentCode(), s.deliveryBatchId(), s.customerId(),
                s.originalEInvoiceId(), s.provider(), s.attempt(), s.transactionId(), s.templateCode(), s.symbol(),
                s.invoiceNumber(), s.taxAuthorityCode(), s.lookupCode(), s.issueDate(), s.issueDueAt(),
                s.dueWarnedAt(), s.signedAt(), s.seller(), s.buyer(), s.currency(), s.paymentMethod(), s.lines(),
                s.subtotal(), s.taxTotal(), s.grandTotal(), s.amountInWords(), s.warnings(), s.adjustmentReason(),
                s.agreementFileKey(), s.xmlFileKey(), s.pdfFileKey(), s.rejectionCode(), s.rejectionMessage(),
                s.submittedAt(), s.submittedBy(), s.issuedAt(), s.voidedAt(), s.voidedBy(), s.voidReason(),
                s.version());
    }
}
