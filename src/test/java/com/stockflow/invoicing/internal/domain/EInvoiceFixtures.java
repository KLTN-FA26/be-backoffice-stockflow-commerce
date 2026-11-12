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
                false);
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
                // As its replacement is issued (E4).
                issue(invoice);
                invoice.replaced();
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
                s.version(), s.correctionBasis(), s.agreementNumber(), s.agreementDate(), s.returnRequestId(),
                s.listedInvoiceIds());
    }

    // ---- corrections (WF-05 E4; TT 91 Điều 10) --------------------------------------------------------------------

    /** The day a correction is drawn up: the day after the delivery, a Friday. */
    public static final LocalDate CORRECTED_ON = DELIVERED_ON.plusDays(1);

    /** An issued original of another delivery to the same buyer, numbered {@code number}. */
    public static EInvoice issuedFor(UUID customerId, int number) {
        EInvoice.Delivery other = delivery();
        EInvoice invoice = EInvoice.drawUpOriginal(UUID.randomUUID(), "EINV-20261112-%04d".formatted(number),
                new EInvoice.Delivery(other.orderId(), other.orderNumber(), other.contractNumber(), other.shipmentId(),
                        other.shipmentCode(), null, customerId, DELIVERED_ON), SELLER, BUYER, lines(), false);
        invoice.submit(SERIES, null, NOW);
        invoice.issued(number, "SBX" + number, "SBXL" + number, NOW, "einvoices/2026/11/12/" + number + ".xml",
                "einvoices/2026/11/12/" + number + ".pdf", NOW);
        return invoice;
    }

    /** The goods line of {@code invoice} changed by {@code cartons} cartons at its own price: below zero reduces. */
    public static List<EInvoiceLine> cartons(EInvoice invoice, int cartons) {
        EInvoiceLine goods = invoice.lines().getFirst();
        return List.of(goods.corrected(UUID.randomUUID(), 1, cartons, goods.unitsPerCarton(), goods.unit(),
                goods.unitPrice(), goods.taxRate(), goods.itemName()));
    }

    /** A draft adjustment of {@code original} by {@code cartons} cartons; nothing else corrects the invoice yet. */
    public static EInvoice adjustment(EInvoice original, CorrectionBasis basis, int cartons) {
        return EInvoice.drawUpAdjustment(UUID.randomUUID(), "EINV-20261113-0001", original, basis,
                "sai so luong", original.buyer(), cartons(original, cartons), CORRECTED_ON,
                basis == CorrectionBasis.RETURN ? UUID.randomUUID() : null, List.of(), EInvoice.Corrections.NONE);
    }

    /** A draft replacement of {@code original} stating {@code cartons} cartons. */
    public static EInvoice replacement(EInvoice original, int cartons) {
        return EInvoice.drawUpReplacement(UUID.randomUUID(), "EINV-20261113-0002", original, "sai so luong",
                original.buyer(), cartons(original, cartons), CORRECTED_ON, EInvoice.Corrections.NONE);
    }

    /** The written agreement of both parties, attached. */
    public static EInvoice agreed(EInvoice correction) {
        correction.attachAgreement("einvoices/2026/11/13/agreement.pdf", "BB-01/2026", CORRECTED_ON, CORRECTED_ON);
        return correction;
    }

    /** A correction sent and issued under {@code number}. */
    public static EInvoice issued(EInvoice correction, int number) {
        correction.submit(SERIES, null, NOW);
        correction.issued(number, "SBX" + number, "SBXL" + number, NOW, "einvoices/2026/11/13/" + number + ".xml",
                "einvoices/2026/11/13/" + number + ".pdf", NOW);
        return correction;
    }
}
