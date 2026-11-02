package com.stockflow.procurement.internal.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

/** SCRUM-309 (313 quantity, 314 price) as plain rules: what the match finds on lines against their PO and receipts. */
class ThreeWayMatchTest {

    private static final UUID ORDER = UUID.randomUUID();
    private static final UUID PO_LINE = UUID.randomUUID();
    private static final UUID RECEIPT_LINE = UUID.randomUUID();
    private static final BigDecimal TWO_PERCENT = new BigDecimal("2.00");

    private static SupplierInvoice.Line line(int quantity, String price) {
        return line(PO_LINE, null, quantity, price, "10");
    }

    private static SupplierInvoice.Line line(UUID poLine, UUID receiptLine, int quantity, String price, String rate) {
        return new SupplierInvoice.Line(UUID.randomUUID(), poLine, receiptLine, quantity, new BigDecimal(price),
                new BigDecimal(rate));
    }

    /** A STANDARD order line at 1 000 net, 10 % tax. */
    private static ThreeWayMatch.PoLineBasis standard(int billable, int awaitingQc, int invoiced) {
        return new ThreeWayMatch.PoLineBasis(PO_LINE, ORDER, false, new BigDecimal("1000"), new BigDecimal("10"),
                billable, awaitingQc, invoiced);
    }

    private static List<SupplierInvoice.Variance> match(List<SupplierInvoice.Line> lines,
                                                        ThreeWayMatch.PoLineBasis po) {
        return ThreeWayMatch.match(lines, Map.of(PO_LINE, po), Map.of(), Map.of(), TWO_PERCENT, UUID::randomUUID);
    }

    @Nested
    @DisplayName("quantity (SCRUM-313)")
    class Quantity {

        @Test
        @DisplayName("billing what was received and passed QC matches")
        void withinReceived() {
            assertThat(match(List.of(line(5, "1000")), standard(5, 0, 0))).isEmpty();
        }

        @Test
        @DisplayName("billing beyond it is an over-invoice: expected what was left, the excess priced at the invoice")
        void overInvoiced() {
            assertThat(match(List.of(line(7, "1000")), standard(5, 0, 0)))
                    .extracting(SupplierInvoice.Variance::kind, v -> v.expected().intValue(),
                            v -> v.invoiced().intValue(), v -> v.amount().toPlainString())
                    .containsExactly(tuple(InvoiceVarianceKind.OVER_INVOICED, 5, 7, "2000.00"));
        }

        @Test
        @DisplayName("units an earlier MATCHED invoice billed are not billed again (BR-05)")
        void billedAlready() {
            assertThat(match(List.of(line(3, "1000")), standard(10, 0, 8)))
                    .extracting(SupplierInvoice.Variance::kind, v -> v.expected().intValue())
                    .containsExactly(tuple(InvoiceVarianceKind.OVER_INVOICED, 2));
        }

        @Test
        @DisplayName("units QC has not concluded on are awaited, not over-invoiced; beyond them they are")
        void awaitingQc() {
            assertThat(match(List.of(line(6, "1000")), standard(2, 4, 0)))
                    .extracting(SupplierInvoice.Variance::kind).containsExactly(InvoiceVarianceKind.AWAITING_QC);
            assertThat(match(List.of(line(7, "1000")), standard(2, 4, 0)))
                    .extracting(SupplierInvoice.Variance::kind).containsExactly(InvoiceVarianceKind.OVER_INVOICED);
        }

        @Test
        @DisplayName("an invoice ahead of the goods: nothing received, everything over-invoiced")
        void nothingReceived() {
            assertThat(match(List.of(line(4, "1000")), standard(0, 0, 0)))
                    .extracting(SupplierInvoice.Variance::kind, v -> v.expected().intValue())
                    .containsExactly(tuple(InvoiceVarianceKind.OVER_INVOICED, 0));
        }

        @Test
        @DisplayName("two lines of one invoice on one PO line add up")
        void linesAddUp() {
            SupplierInvoice.Line first = line(PO_LINE, null, 3, "1000", "10");
            SupplierInvoice.Line second = line(PO_LINE, RECEIPT_LINE, 3, "1000", "10");
            List<SupplierInvoice.Variance> found = ThreeWayMatch.match(List.of(first, second),
                    Map.of(PO_LINE, standard(5, 0, 0)),
                    Map.of(RECEIPT_LINE, new ThreeWayMatch.ReceiptLineBasis(RECEIPT_LINE, 5, 0, 0)), Map.of(),
                    TWO_PERCENT, UUID::randomUUID);
            assertThat(found).extracting(SupplierInvoice.Variance::lineId, SupplierInvoice.Variance::kind,
                            v -> v.expected().intValue())
                    .containsExactly(tuple(second.id(), InvoiceVarianceKind.OVER_INVOICED, 2));
        }

        @Test
        @DisplayName("a line naming its delivery is held to it too - unless the order already says the units are not there")
        void receiptLine() {
            SupplierInvoice.Line billed = line(PO_LINE, RECEIPT_LINE, 3, "1000", "10");
            assertThat(ThreeWayMatch.match(List.of(billed), Map.of(PO_LINE, standard(10, 0, 0)),
                    Map.of(RECEIPT_LINE, new ThreeWayMatch.ReceiptLineBasis(RECEIPT_LINE, 2, 0, 0)), Map.of(),
                    TWO_PERCENT, UUID::randomUUID))
                    .extracting(SupplierInvoice.Variance::kind, v -> v.expected().intValue())
                    .containsExactly(tuple(InvoiceVarianceKind.OVER_INVOICED_RECEIPT, 2));
            assertThat(ThreeWayMatch.match(List.of(billed), Map.of(PO_LINE, standard(2, 0, 0)),
                    Map.of(RECEIPT_LINE, new ThreeWayMatch.ReceiptLineBasis(RECEIPT_LINE, 2, 0, 0)), Map.of(),
                    TWO_PERCENT, UUID::randomUUID))
                    .extracting(SupplierInvoice.Variance::kind).containsExactly(InvoiceVarianceKind.OVER_INVOICED);
        }
    }

    @Nested
    @DisplayName("price and tax (SCRUM-314)")
    class Price {

        @Test
        @DisplayName("exactly at the tolerance either way passes; a cent beyond it is a price variance")
        void tolerance() {
            assertThat(match(List.of(line(1, "1020")), standard(1, 0, 0))).isEmpty();
            assertThat(match(List.of(line(1, "980")), standard(1, 0, 0))).isEmpty();
            assertThat(match(List.of(line(2, "1020.01")), standard(2, 0, 0)))
                    .extracting(SupplierInvoice.Variance::kind, v -> v.amount().toPlainString())
                    .containsExactly(tuple(InvoiceVarianceKind.PRICE, "40.02"));
            assertThat(match(List.of(line(2, "970")), standard(2, 0, 0)))
                    .extracting(SupplierInvoice.Variance::kind, v -> v.amount().toPlainString())
                    .as("below the order is flagged too, at a negative amount")
                    .containsExactly(tuple(InvoiceVarianceKind.PRICE, "-60.00"));
        }

        @Test
        @DisplayName("against the order's net price, after its discount")
        void discount() {
            InvoiceMatchFacts.OrderLine discounted = new InvoiceMatchFacts.OrderLine(PO_LINE, ORDER, "PO-1",
                    UUID.randomUUID(), PurchaseOrder.Type.STANDARD, PurchaseOrderStatus.RECEIVED, "VND", 1, "CUP", 10,
                    new BigDecimal("1000.00"), new BigDecimal("10.00"), new BigDecimal("10.00"));
            ThreeWayMatch.PoLineBasis basis = new ThreeWayMatch.PoLineBasis(PO_LINE, ORDER, false,
                    discounted.netUnitPrice(), discounted.taxRate(), 10, 0, 0);
            assertThat(match(List.of(line(10, "900")), basis)).isEmpty();
            assertThat(match(List.of(line(10, "1000")), basis)).extracting(SupplierInvoice.Variance::kind)
                    .containsExactly(InvoiceVarianceKind.PRICE);
        }

        @Test
        @DisplayName("a free line billed at any price is a variance; at 0 it matches")
        void freeLine() {
            ThreeWayMatch.PoLineBasis free = new ThreeWayMatch.PoLineBasis(PO_LINE, ORDER, false, BigDecimal.ZERO,
                    new BigDecimal("10"), 2, 0, 0);
            assertThat(match(List.of(line(2, "0")), free)).isEmpty();
            assertThat(match(List.of(line(2, "1")), free)).extracting(SupplierInvoice.Variance::kind)
                    .containsExactly(InvoiceVarianceKind.PRICE);
        }

        @Test
        @DisplayName("another tax rate is a variance of the tax it adds; a line can be off on several counts at once")
        void taxRate() {
            SupplierInvoice.Line eight = line(PO_LINE, null, 6, "1100", "8");
            assertThat(match(List.of(eight), standard(5, 0, 0)))
                    .extracting(SupplierInvoice.Variance::kind, v -> v.amount().toPlainString())
                    .containsExactly(tuple(InvoiceVarianceKind.OVER_INVOICED, "1100.00"),
                            tuple(InvoiceVarianceKind.PRICE, "600.00"),
                            tuple(InvoiceVarianceKind.TAX_RATE, "-132.00"));
        }
    }

    @Nested
    @DisplayName("subcontract orders (BR-PRD-17)")
    class Subcontract {

        private ThreeWayMatch.PoLineBasis printWork(int passed) {
            return new ThreeWayMatch.PoLineBasis(PO_LINE, ORDER, true, new BigDecimal("1000"), new BigDecimal("10"),
                    passed, 0, 0);
        }

        private List<SupplierInvoice.Variance> match(int quantity, ThreeWayMatch.SettlementBasis settlement) {
            return ThreeWayMatch.match(List.of(line(quantity, "1000")),
                    Map.of(PO_LINE, printWork(settlement.passedQuantity())), Map.of(), Map.of(ORDER, settlement),
                    TWO_PERCENT, UUID::randomUUID);
        }

        @Test
        @DisplayName("nothing is billable before the settlement is final")
        void pending() {
            assertThat(match(10, new ThreeWayMatch.SettlementBasis(ORDER, false, 0, BigDecimal.ZERO, BigDecimal.ZERO)))
                    .extracting(SupplierInvoice.Variance::kind).containsExactly(InvoiceVarianceKind.SETTLEMENT_PENDING);
        }

        @Test
        @DisplayName("the passed units at the price, less the loss deduction: billing the deduction is above the settlement")
        void aboveSettlement() {
            ThreeWayMatch.SettlementBasis settlement = new ThreeWayMatch.SettlementBasis(ORDER, true, 10,
                    new BigDecimal("9500.00"), BigDecimal.ZERO);
            assertThat(match(10, settlement))
                    .extracting(SupplierInvoice.Variance::kind, SupplierInvoice.Variance::lineId,
                            SupplierInvoice.Variance::purchaseOrderId, v -> v.amount().toPlainString())
                    .containsExactly(tuple(InvoiceVarianceKind.ABOVE_SETTLEMENT, null, ORDER, "500.00"));
            assertThat(match(10, new ThreeWayMatch.SettlementBasis(ORDER, true, 10, new BigDecimal("9900.00"),
                    BigDecimal.ZERO))).as("within the tolerance of the net payable").isEmpty();
            assertThat(match(10, new ThreeWayMatch.SettlementBasis(ORDER, true, 10, new BigDecimal("10000.00"),
                    BigDecimal.ZERO))).isEmpty();
        }
    }
}
