package com.stockflow.procurement.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/** SCRUM-308 as plain rules: what an invoice holds by itself, its amounts, and which status allows what. */
class SupplierInvoiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 2);
    private static final Instant NOW = Instant.parse("2026-11-02T03:00:00Z");
    private static final UUID ACCOUNTANT = UUID.randomUUID();
    private static final UUID PO_LINE = UUID.randomUUID();

    private static ErrorCode refusal(ThrowableAssert.ThrowingCallable call) {
        Throwable thrown = catchThrowable(call);
        assertThat(thrown).isInstanceOf(BusinessException.class);
        return ((BusinessException) thrown).errorCode();
    }

    private static SupplierInvoice.Header header(String number, LocalDate date, LocalDate due) {
        return new SupplierInvoice.Header(null, number, date, due, null);
    }

    private static SupplierInvoice.NewLine line(UUID poLine, int quantity, String price, String rate) {
        return new SupplierInvoice.NewLine(poLine, null, quantity, new BigDecimal(price),
                rate == null ? null : new BigDecimal(rate));
    }

    private static SupplierInvoice enter(SupplierInvoice.Header header, List<SupplierInvoice.NewLine> lines) {
        return SupplierInvoice.enter(UUID.randomUUID(), UUID.randomUUID(), "VND", header, lines, ACCOUNTANT, NOW, TODAY,
                UUID::randomUUID);
    }

    private static SupplierInvoice entered() {
        return enter(header(" HD-001 ", TODAY, TODAY.plusDays(30)), List.of(line(PO_LINE, 3, "1000.50", "10")));
    }

    @Test
    @DisplayName("amounts come from the lines: net, tax rounded half up to the cent per line, and their sums")
    void amounts() {
        SupplierInvoice invoice = enter(header("HD-001", TODAY, null), List.of(line(PO_LINE, 3, "1000.50", "10"),
                line(UUID.randomUUID(), 1, "0.05", "10"), line(UUID.randomUUID(), 2, "100", null)));
        assertThat(invoice.lines()).extracting(l -> l.netAmount().toPlainString(), l -> l.taxAmount().toPlainString(),
                        l -> l.total().toPlainString())
                .containsExactly(org.assertj.core.groups.Tuple.tuple("3001.50", "300.15", "3301.65"),
                        org.assertj.core.groups.Tuple.tuple("0.05", "0.01", "0.06"),
                        org.assertj.core.groups.Tuple.tuple("200.00", "0.00", "200.00"));
        assertThat(invoice.subtotal()).isEqualByComparingTo("3201.55");
        assertThat(invoice.taxTotal()).isEqualByComparingTo("300.16");
        assertThat(invoice.total()).isEqualByComparingTo("3501.71");
        assertThat(invoice.status()).isEqualTo(SupplierInvoiceStatus.DRAFT);
        assertThat(invoice.invoiceNumber()).isEqualTo("HD-001");
    }

    @Test
    @DisplayName("what an invoice holds by itself: number, a date not after today, a due date not before it, lines")
    void entryRules() {
        List<SupplierInvoice.NewLine> one = List.of(line(PO_LINE, 1, "1", "0"));
        assertThat(refusal(() -> enter(header(" ", TODAY, null), one))).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(refusal(() -> enter(header("A", TODAY.plusDays(1), null), one)))
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(refusal(() -> enter(header("A", TODAY, TODAY.minusDays(1)), one)))
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(refusal(() -> enter(header("A", null, null), one))).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(refusal(() -> enter(header("A", TODAY, null), List.of()))).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(refusal(() -> enter(header("A", TODAY, null), List.of(line(PO_LINE, 0, "1", "0")))))
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(refusal(() -> enter(header("A", TODAY, null), List.of(line(PO_LINE, 1, "1.001", "0")))))
                .as("a price beyond the cent").isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(refusal(() -> enter(header("A", TODAY, null), List.of(line(PO_LINE, 1, "-1", "0")))))
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(refusal(() -> enter(header("A", TODAY, null), List.of(line(PO_LINE, 1, "1", "100.01")))))
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(refusal(() -> enter(header("A", TODAY, null), List.of(line(PO_LINE, 1, "1", "0"),
                line(PO_LINE, 2, "1", "0"))))).as("one PO line twice for the same delivery")
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(enter(header("A", TODAY, TODAY), List.of(line(PO_LINE, 1, "1000.000", "8.0"))).lines().getFirst()
                .unitPrice()).isEqualByComparingTo("1000");
    }

    @Test
    @DisplayName("a match with no variance is MATCHED and closes the invoice to edits; with any, EXCEPTION and open")
    void match() {
        SupplierInvoice clean = entered();
        clean.recordMatch(List.of(), new BigDecimal("2.00"), ACCOUNTANT, NOW);
        assertThat(clean.status()).isEqualTo(SupplierInvoiceStatus.MATCHED);
        assertThat(clean.matchedBy()).isEqualTo(ACCOUNTANT);
        assertThat(refusal(() -> clean.revise(header("HD-002", TODAY, null), List.of(line(PO_LINE, 1, "1", "0")),
                TODAY, UUID::randomUUID))).isEqualTo(ErrorCode.SUPPLIER_INVOICE_INVALID_TRANSITION);
        assertThat(refusal(() -> clean.cancel("wrong", ACCOUNTANT, NOW)))
                .isEqualTo(ErrorCode.SUPPLIER_INVOICE_INVALID_TRANSITION);
        assertThat(refusal(() -> clean.recordMatch(List.of(), BigDecimal.ONE, ACCOUNTANT, NOW)))
                .isEqualTo(ErrorCode.SUPPLIER_INVOICE_INVALID_TRANSITION);

        SupplierInvoice off = entered();
        UUID lineId = off.lines().getFirst().id();
        off.recordMatch(List.of(new SupplierInvoice.Variance(UUID.randomUUID(), lineId, null,
                InvoiceVarianceKind.OVER_INVOICED, BigDecimal.ONE, new BigDecimal("3"), new BigDecimal("2001.00"))),
                new BigDecimal("2.00"), ACCOUNTANT, NOW);
        assertThat(off.status()).isEqualTo(SupplierInvoiceStatus.EXCEPTION);
        assertThat(off.variances()).hasSize(1);
    }

    @Test
    @DisplayName("correcting an EXCEPTION drops its variances and takes it back to DRAFT, to be matched afresh")
    void reviseException() {
        SupplierInvoice invoice = entered();
        invoice.recordMatch(List.of(new SupplierInvoice.Variance(UUID.randomUUID(), invoice.lines().getFirst().id(),
                null, InvoiceVarianceKind.PRICE, BigDecimal.TEN, BigDecimal.ONE, BigDecimal.ONE)),
                new BigDecimal("2.00"), ACCOUNTANT, NOW);
        invoice.revise(header("HD-001", TODAY, null), List.of(line(PO_LINE, 1, "1000", "10")), TODAY,
                UUID::randomUUID);
        assertThat(invoice.status()).isEqualTo(SupplierInvoiceStatus.DRAFT);
        assertThat(invoice.variances()).isEmpty();
        assertThat(invoice.matchedAt()).isNull();
        assertThat(invoice.priceTolerancePercent()).isNull();
        assertThat(invoice.lines()).singleElement().satisfies(l -> assertThat(l.quantity()).isEqualTo(1));
    }

    @Test
    @DisplayName("cancelling needs a reason and leaves the invoice closed; a cancelled invoice takes no scan")
    void cancelling() {
        SupplierInvoice invoice = entered();
        assertThat(refusal(() -> invoice.cancel("  ", ACCOUNTANT, NOW))).isEqualTo(ErrorCode.VALIDATION_FAILED);
        invoice.cancel(" nhập nhầm ", ACCOUNTANT, NOW);
        assertThat(invoice.status()).isEqualTo(SupplierInvoiceStatus.CANCELLED);
        assertThat(invoice.cancelReason()).isEqualTo("nhập nhầm");
        assertThat(refusal(() -> invoice.attach(new SupplierInvoice.Document("k", "a.pdf", "application/pdf"))))
                .isEqualTo(ErrorCode.SUPPLIER_INVOICE_INVALID_TRANSITION);

        SupplierInvoice matched = entered();
        matched.recordMatch(List.of(), BigDecimal.ONE, ACCOUNTANT, NOW);
        matched.attach(new SupplierInvoice.Document("k", "a.pdf", "application/pdf"));
        assertThat(matched.document().name()).isEqualTo("a.pdf");
    }
}
