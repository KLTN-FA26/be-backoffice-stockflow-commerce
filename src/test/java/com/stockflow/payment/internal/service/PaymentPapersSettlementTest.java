package com.stockflow.payment.internal.service;

import com.stockflow.common.document.DocumentParts;
import com.stockflow.customer.api.CustomerLegalProfiles;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.customer.api.CustomerSummary;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.payment.api.InstallmentOrders;
import com.stockflow.payment.internal.domain.InstallmentBook;
import com.stockflow.payment.internal.domain.OrderSettlement;
import com.stockflow.payment.internal.domain.RefundRecord;
import com.stockflow.payment.internal.domain.SettlementBook;
import com.stockflow.payment.internal.repository.CustomerLedgerQuery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WF-05 audit round 2 on the money papers:
 * <ul>
 *   <li>money M-3: the debt reconciliation charges an installment at its whole amount the day it fell due and enters
 *       each reduction on its own day ({@code payment.installment_reduction}) - a return approved in November no longer
 *       lowers what October closed at, and November opens at what October closed at;</li>
 *   <li>contract M-1: money settled for a shipment Sales closed as lost (Q-12) is worded as lost - on the refund
 *       confirmation and on the reconciliation - never as goods back at the warehouse.</li>
 * </ul>
 */
class PaymentPapersSettlementTest {

    private static final UUID CUSTOMER = UUID.randomUUID();
    private static final UUID ORDER = UUID.randomUUID();
    private static final UUID LOST = UUID.randomUUID();
    private static final UUID BACK = UUID.randomUUID();
    private static final ResourceBundle T = DocumentParts.bundle();

    private final CustomerLedgerQuery ledger = mock(CustomerLedgerQuery.class);
    private final SettlementBook settlements = mock(SettlementBook.class);
    private final InstallmentOrders orders = mock(InstallmentOrders.class);
    private final CustomerService customers = mock(CustomerService.class);
    private final IdentityService identities = mock(IdentityService.class);
    private final DebtReconciliationDocument debtPaper = mock(DebtReconciliationDocument.class);
    private final RefundConfirmationDocument refundPaper = mock(RefundConfirmationDocument.class);
    private final PaymentPaperService papers = new PaymentPaperService(mock(InstallmentService.class),
            mock(InstallmentBook.class), settlements, ledger, customers, mock(CustomerLegalProfiles.class), identities,
            mock(PaymentConfirmationDocument.class), refundPaper, debtPaper, orders,
            Clock.fixed(Instant.parse("2026-11-30T03:00:00Z"), ZoneOffset.UTC));

    {
        CustomerSummary customer = mock(CustomerSummary.class);
        when(customer.code()).thenReturn("KH-1");
        when(customer.fullName()).thenReturn("Quán cà phê Mộc");
        when(customers.findById(CUSTOMER)).thenReturn(Optional.of(customer));
        when(identities.displayNames(any())).thenReturn(Map.of());
        when(debtPaper.render(any())).thenReturn(new byte[0]);
        when(refundPaper.render(any())).thenReturn(new byte[0]);
        when(orders.lostShipments(any())).thenReturn(Set.of(LOST));
    }

    private static LocalDate day(int month, int day) {
        return LocalDate.of(2026, month, day);
    }

    private DebtReconciliationDocument.Facts reconcile(LocalDate from, LocalDate to) {
        papers.debtReconciliation(CUSTOMER, from, to);
        ArgumentCaptor<DebtReconciliationDocument.Facts> facts =
                ArgumentCaptor.forClass(DebtReconciliationDocument.Facts.class);
        verify(debtPaper, org.mockito.Mockito.atLeastOnce()).render(facts.capture());
        return facts.getValue();
    }

    private static BigDecimal closing(DebtReconciliationDocument.Facts facts) {
        return facts.entries().stream().map(entry -> entry.debit().subtract(entry.credit()))
                .reduce(facts.opening(), BigDecimal::add);
    }

    @Test
    @DisplayName("money M-3: 70 tr due 05/10 and unpaid, a return of 50 tr approved 10/11 - October closes at 70 tr "
            + "whenever it is printed, November opens at 70 tr and shows the 50 tr reduction on 10/11")
    void eachReductionLandsInItsOwnPeriod() {
        when(ledger.charges(CUSTOMER)).thenReturn(List.of(new CustomerLedgerQuery.Charge(day(10, 5), "SO-1", "HD-1",
                2, "Sau giao hàng", "AFTER_DELIVERY", new BigDecimal("70000000"), new BigDecimal("50000000"),
                new BigDecimal("20000000"), day(11, 4), false)));
        when(ledger.reductions(CUSTOMER)).thenReturn(List.of(new CustomerLedgerQuery.Reduction(day(11, 10),
                "GOODS_RETURNED", "SO-1", "HD-1", null, 2, new BigDecimal("50000000"))));
        when(ledger.settlements(CUSTOMER)).thenReturn(List.of(new CustomerLedgerQuery.Settlement(day(11, 10),
                "GOODS_RETURNED", "SO-1", "HD-1", BigDecimal.ZERO, BigDecimal.ZERO, null)));

        DebtReconciliationDocument.Facts october = reconcile(day(10, 1), day(10, 31));
        assertThat(october.opening()).isEqualByComparingTo("0");
        assertThat(closing(october)).as("what both parties signed for October").isEqualByComparingTo("70000000");

        DebtReconciliationDocument.Facts november = reconcile(day(11, 1), day(11, 30));
        assertThat(november.opening()).as("carried forward").isEqualByComparingTo("70000000");
        assertThat(november.entries()).singleElement().satisfies(entry -> {
            assertThat(entry.date()).isEqualTo(day(11, 10));
            assertThat(entry.credit()).isEqualByComparingTo("50000000");
            assertThat(entry.description()).isEqualTo(T.getString("payment-papers.debt.returned").formatted("SO-1"));
        });
        assertThat(closing(november)).isEqualByComparingTo("20000000");
    }

    @Test
    @DisplayName("contract M-1: a lost shipment's reduction and its refundable money read \"thất lạc\", a shipment "
            + "brought back still reads as back at the warehouse")
    void aLostShipmentIsWordedAsLostOnTheReconciliation() {
        when(ledger.charges(CUSTOMER)).thenReturn(List.of(new CustomerLedgerQuery.Charge(day(11, 1), "SO-1", "HD-1",
                2, "Sau giao hàng", "AFTER_DELIVERY", new BigDecimal("1000"), new BigDecimal("300"),
                new BigDecimal("700"), day(12, 1), false)));
        when(ledger.reductions(CUSTOMER)).thenReturn(List.of(
                new CustomerLedgerQuery.Reduction(day(11, 5), "SHIPMENT_RETURNED", "SO-1", "HD-1", LOST, 2,
                        new BigDecimal("200")),
                new CustomerLedgerQuery.Reduction(day(11, 6), "SHIPMENT_RETURNED", "SO-1", "HD-1", BACK, 2,
                        new BigDecimal("100"))));
        when(ledger.settlements(CUSTOMER)).thenReturn(List.of(
                new CustomerLedgerQuery.Settlement(day(11, 5), "SHIPMENT_RETURNED", "SO-1", "HD-1", BigDecimal.ZERO,
                        new BigDecimal("50"), LOST),
                new CustomerLedgerQuery.Settlement(day(11, 6), "REMAINDER_CLOSED", "SO-1", "HD-1", BigDecimal.ZERO,
                        new BigDecimal("10"), null)));

        DebtReconciliationDocument.Facts facts = reconcile(day(11, 1), day(11, 30));

        String lost = T.getString("payment-papers.debt.shipmentLost").formatted("SO-1");
        String back = T.getString("payment-papers.debt.shipmentReturned").formatted("SO-1");
        assertThat(lost).contains("thất lạc").doesNotContain("hoàn về kho");
        assertThat(facts.entries()).extracting(DebtReconciliationDocument.Entry::description)
                .containsExactly(T.getString("payment-papers.debt.charge").formatted(2, "Sau giao hàng", "SO-1"),
                        lost, lost, back, T.getString("payment-papers.debt.closed").formatted("SO-1"));
    }

    @Test
    @DisplayName("audit r3 (with r3a): goods money a closure moved onto its fee item is worded as goods money, the fee "
            + "as a fee, and a receipt paying that item as the closure's amount - never all of it as a cancellation fee")
    void goodsMoneyCarriedOntoAFeeItemIsNotAFee() {
        when(ledger.charges(CUSTOMER)).thenReturn(List.of(new CustomerLedgerQuery.Charge(day(11, 10), "SO-1", "HD-1",
                3, "Phí", "CANCELLATION_FEE", new BigDecimal("1000"), BigDecimal.ZERO, new BigDecimal("1000"),
                day(11, 20), false, new BigDecimal("700"))));
        when(ledger.receipts(CUSTOMER)).thenReturn(List.of(new CustomerLedgerQuery.Receipt(day(11, 15), "FT-9",
                "HD-1", 3, true, new BigDecimal("1000"), true)));

        DebtReconciliationDocument.Facts facts = reconcile(day(11, 1), day(11, 30));

        assertThat(facts.entries()).extracting(DebtReconciliationDocument.Entry::description, e -> e.debit()
                .add(e.credit()).stripTrailingZeros().toPlainString()).containsExactly(
                org.assertj.core.groups.Tuple.tuple(T.getString("payment-papers.debt.carriedToFee").formatted("SO-1"),
                        "700"),
                org.assertj.core.groups.Tuple.tuple(T.getString("payment-papers.debt.fee").formatted(
                        T.getString("payment-papers.fee.CANCELLATION_FEE"), "SO-1"), "300"),
                org.assertj.core.groups.Tuple.tuple(T.getString("payment-papers.debt.receiptCarried")
                        .formatted("HD-1"), "1000"));
        assertThat(closing(facts)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("contract M-1: the refund confirmation of a lost shipment's money gives the loss as its reason")
    void aLostShipmentIsWordedAsLostOnTheRefundConfirmation() {
        UUID recordId = UUID.randomUUID();
        RefundRecord record = mock(RefundRecord.class);
        when(record.status()).thenReturn(RefundRecord.Status.RECORDED);
        when(record.customerId()).thenReturn(CUSTOMER);
        when(record.orderId()).thenReturn(ORDER);
        when(record.orderNumber()).thenReturn("SO-1");
        when(record.amount()).thenReturn(new BigDecimal("50"));
        when(record.refundedOn()).thenReturn(day(11, 20));
        when(record.recordedAt()).thenReturn(Instant.parse("2026-11-20T03:00:00Z"));
        when(record.reference()).thenReturn("FT-1");
        when(settlements.findRecord(recordId)).thenReturn(Optional.of(record));
        OrderSettlement settled = mock(OrderSettlement.class);
        when(settled.kind()).thenReturn(OrderSettlement.Kind.SHIPMENT_RETURNED);
        when(settled.shipmentId()).thenReturn(LOST);
        when(settled.refundable()).thenReturn(new BigDecimal("50"));
        when(settled.contractNumber()).thenReturn("HD-1");
        when(settled.settledAt()).thenReturn(Instant.parse("2026-11-18T03:00:00Z"));
        when(settlements.ofOrder(ORDER)).thenReturn(List.of(settled));

        papers.refundConfirmation(recordId, null);

        ArgumentCaptor<RefundConfirmationDocument.Facts> facts =
                ArgumentCaptor.forClass(RefundConfirmationDocument.Facts.class);
        verify(refundPaper).render(facts.capture());
        assertThat(facts.getValue().reason()).isEqualTo(T.getString("payment-papers.refund.SHIPMENT_LOST"))
                .contains("thất lạc");
    }

    @Test
    @DisplayName("confirmation audit CF-11: refund 1 of a carrier return, reprinted after a customer return of the same "
            + "order was refunded months later, gives only the carrier return as its reason")
    void aRefundsReasonIsWhatHadMadeMoneyRefundableWhenItWasRecorded() {
        UUID recordId = UUID.randomUUID();
        RefundRecord record = mock(RefundRecord.class);
        when(record.status()).thenReturn(RefundRecord.Status.RECORDED);
        when(record.customerId()).thenReturn(CUSTOMER);
        when(record.orderId()).thenReturn(ORDER);
        when(record.orderNumber()).thenReturn("SO-1");
        when(record.amount()).thenReturn(new BigDecimal("50"));
        when(record.refundedOn()).thenReturn(day(9, 20));
        when(record.recordedAt()).thenReturn(Instant.parse("2026-09-20T03:00:00Z"));
        when(record.reference()).thenReturn("FT-1");
        when(settlements.findRecord(recordId)).thenReturn(Optional.of(record));
        OrderSettlement carrierReturn = mock(OrderSettlement.class);
        when(carrierReturn.kind()).thenReturn(OrderSettlement.Kind.SHIPMENT_RETURNED);
        when(carrierReturn.shipmentId()).thenReturn(BACK);
        when(carrierReturn.refundable()).thenReturn(new BigDecimal("50"));
        when(carrierReturn.contractNumber()).thenReturn("HD-1");
        when(carrierReturn.settledAt()).thenReturn(Instant.parse("2026-09-18T03:00:00Z"));
        OrderSettlement customerReturn = mock(OrderSettlement.class);
        when(customerReturn.kind()).thenReturn(OrderSettlement.Kind.GOODS_RETURNED);
        when(customerReturn.refundable()).thenReturn(new BigDecimal("30"));
        when(customerReturn.contractNumber()).thenReturn("HD-1");
        when(customerReturn.settledAt()).thenReturn(Instant.parse("2026-11-25T03:00:00Z"));
        when(settlements.ofOrder(ORDER)).thenReturn(List.of(carrierReturn, customerReturn));

        papers.refundConfirmation(recordId, null);

        ArgumentCaptor<RefundConfirmationDocument.Facts> facts =
                ArgumentCaptor.forClass(RefundConfirmationDocument.Facts.class);
        verify(refundPaper).render(facts.capture());
        assertThat(facts.getValue().reason()).isEqualTo(T.getString("payment-papers.refund.SHIPMENT_RETURNED"));
    }
}
