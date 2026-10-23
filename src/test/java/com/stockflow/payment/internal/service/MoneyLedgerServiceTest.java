package com.stockflow.payment.internal.service;

import com.stockflow.common.error.ErrorCode;
import com.stockflow.contracts.PaymentCaptured;
import com.stockflow.contracts.PaymentMethod;
import com.stockflow.contracts.PaymentReversed;
import com.stockflow.payment.api.OrderPayables;
import com.stockflow.payment.api.PayableOrder;
import com.stockflow.payment.internal.domain.CustomerTransfer;
import com.stockflow.payment.internal.domain.Receivable;
import com.stockflow.payment.internal.domain.ReceivableBook;
import com.stockflow.payment.internal.domain.ReceivableStatus;
import com.stockflow.payment.internal.domain.TransferChannel;
import com.stockflow.payment.internal.service.MoneyLedgerService.AllocateCommand;
import com.stockflow.payment.internal.service.MoneyLedgerService.AllocationResult;
import com.stockflow.payment.internal.service.MoneyLedgerService.CarrierRemittanceCommand;
import com.stockflow.payment.internal.service.MoneyLedgerService.CreditRefundCommand;
import com.stockflow.payment.internal.service.MoneyLedgerService.ReverseCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.stockflow.payment.internal.service.PaymentFixtures.NOW;
import static com.stockflow.payment.internal.service.PaymentFixtures.TODAY;
import static com.stockflow.payment.internal.service.PaymentFixtures.allocation;
import static com.stockflow.payment.internal.service.PaymentFixtures.codeOf;
import static com.stockflow.payment.internal.service.PaymentFixtures.order;
import static com.stockflow.payment.internal.service.PaymentFixtures.receivable;
import static com.stockflow.payment.internal.service.PaymentFixtures.transfer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** kltn-docs 15 §2, §4.5, BR-04..BR-07: what the accountant does with money already recorded. */
class MoneyLedgerServiceTest {

    private final ReceivableBook book = mock(ReceivableBook.class);
    private final OrderPayables orders = mock(OrderPayables.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final OrderAllocator orderAllocator = new OrderAllocator(book, orders, events, clock);
    private final MoneyLedgerService service = new MoneyLedgerService(book, orders, orderAllocator, events, clock);

    private final UUID customer = UUID.randomUUID();
    private final UUID by = UUID.randomUUID();

    @BeforeEach
    void saveReturnsArgument() {
        when(book.save(any(CustomerTransfer.class))).thenAnswer(call -> call.getArgument(0));
        when(book.save(any(Receivable.class))).thenAnswer(call -> call.getArgument(0));
    }

    private CustomerTransfer knownTransfer(String amount) {
        CustomerTransfer t = transfer(customer, amount);
        when(book.findTransfer(t.id())).thenReturn(Optional.of(t));
        when(book.lockTransfer(t.id())).thenReturn(Optional.of(t));
        return t;
    }

    // ------------------------------------------------------------------ allocate

    @Nested
    class Allocate {

        @Test
        @DisplayName("name an order or a receivable: neither and both are VALIDATION_FAILED")
        void exactlyOneTarget() {
            UUID transfer = UUID.randomUUID();
            assertThat(codeOf(() -> service.allocate(new AllocateCommand(transfer, null, null, BigDecimal.TEN, by))))
                    .isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThat(codeOf(() -> service.allocate(
                    new AllocateCommand(transfer, UUID.randomUUID(), UUID.randomUUID(), BigDecimal.TEN, by))))
                    .isEqualTo(ErrorCode.VALIDATION_FAILED);
            verifyNoInteractions(book);
        }

        @Test
        @DisplayName("an unknown transfer is TRANSFER_NOT_FOUND")
        void unknownTransfer() {
            UUID missing = UUID.randomUUID();
            when(book.findTransfer(missing)).thenReturn(Optional.empty());
            assertThat(codeOf(() -> service.allocate(
                    new AllocateCommand(missing, UUID.randomUUID(), null, BigDecimal.TEN, by))))
                    .isEqualTo(ErrorCode.TRANSFER_NOT_FOUND);
        }

        @Test
        @DisplayName("credit to an order (money before its order, 15 edge): the order takes it and hears PaymentCaptured")
        void toAnOrder() {
            CustomerTransfer t = knownTransfer("1000");
            UUID orderId = UUID.randomUUID();
            PayableOrder o = order(orderId, customer, "PENDING_PAYMENT", "PREPAID", "600");
            when(orders.lockForPayment(orderId)).thenReturn(Optional.of(o));
            when(book.collectedFor(orderId)).thenReturn(BigDecimal.ZERO);
            when(book.outstandingReceivablesOf(orderId)).thenReturn(BigDecimal.ZERO);
            ReceivableBook.Allocation recorded = allocation(t.id(), orderId, null, "600");
            when(book.recordAllocation(any(), any(), any(), any(), any(), any())).thenReturn(recorded);

            AllocationResult result = service.allocate(new AllocateCommand(t.id(), orderId, null,
                    new BigDecimal("600"), by));

            assertThat(result.allocation()).isSameAs(recorded);
            assertThat(result.transfer().unallocatedAmount()).isEqualByComparingTo("400");
            verify(book).save(t);
            ArgumentCaptor<PaymentCaptured> event = ArgumentCaptor.forClass(PaymentCaptured.class);
            verify(events).publishEvent(event.capture());
            assertThat(event.getValue().source()).isEqualTo("ORDER_ALLOCATION");
        }

        @Test
        @DisplayName("an order of another customer than the transfer's is ALLOCATION_CUSTOMER_MISMATCH")
        void orderOfAnotherCustomer() {
            CustomerTransfer t = knownTransfer("1000");
            UUID orderId = UUID.randomUUID();
            when(orders.lockForPayment(orderId)).thenReturn(
                    Optional.of(order(orderId, UUID.randomUUID(), "CONFIRMED", "PREPAID", "600")));
            assertThat(codeOf(() -> service.allocate(new AllocateCommand(t.id(), orderId, null, BigDecimal.TEN, by))))
                    .isEqualTo(ErrorCode.ALLOCATION_CUSTOMER_MISMATCH);
            verify(book, never()).lockTransfer(any());
        }

        private Receivable lockedReceivable(String amount) {
            Receivable r = receivable(customer, amount, TODAY.plusDays(5));
            when(book.lockReceivable(r.id())).thenReturn(Optional.of(r));
            when(book.recordAllocation(any(), any(), any(), any(), any(), any())).thenAnswer(call ->
                    new ReceivableBook.Allocation(UUID.randomUUID(), call.getArgument(0), call.getArgument(1),
                            call.getArgument(2), call.getArgument(3), NOW, by, null, null));
            return r;
        }

        @Test
        @DisplayName("an unknown receivable is RECEIVABLE_NOT_FOUND")
        void unknownReceivable() {
            CustomerTransfer t = knownTransfer("1000");
            UUID missing = UUID.randomUUID();
            when(book.lockReceivable(missing)).thenReturn(Optional.empty());
            assertThat(codeOf(() -> service.allocate(new AllocateCommand(t.id(), null, missing, null, by))))
                    .isEqualTo(ErrorCode.RECEIVABLE_NOT_FOUND);
        }

        @Test
        @DisplayName("another customer's receivable is ALLOCATION_CUSTOMER_MISMATCH")
        void receivableOfAnotherCustomer() {
            CustomerTransfer t = knownTransfer("1000");
            Receivable other = receivable(UUID.randomUUID(), "500", TODAY);
            when(book.lockReceivable(other.id())).thenReturn(Optional.of(other));
            assertThat(codeOf(() -> service.allocate(new AllocateCommand(t.id(), null, other.id(), null, by))))
                    .isEqualTo(ErrorCode.ALLOCATION_CUSTOMER_MISMATCH);
        }

        @Test
        @DisplayName("a customer's bank transfer never pays what the carrier collected (09 §2)")
        void carrierCollectedRefusesBankTransfer() {
            CustomerTransfer t = knownTransfer("1000");
            Receivable cod = Receivable.openCarrierCollected(UUID.randomUUID(), customer, new BigDecimal("500"), "VND",
                    NOW, TODAY);
            when(book.lockReceivable(cod.id())).thenReturn(Optional.of(cod));
            assertThat(codeOf(() -> service.allocate(new AllocateCommand(t.id(), null, cod.id(), null, by))))
                    .isEqualTo(ErrorCode.RECEIVABLE_CARRIER_COLLECTED);
        }

        @Test
        @DisplayName("no amount named: pays the lesser of the receivable's outstanding and the transfer's credit")
        void defaultAmountIsTheLesser() {
            CustomerTransfer t = knownTransfer("300");
            Receivable r = lockedReceivable("1000");

            AllocationResult result = service.allocate(new AllocateCommand(t.id(), null, r.id(), null, by));

            assertThat(result.allocation().amount()).isEqualByComparingTo("300");
            assertThat(r.status()).isEqualTo(ReceivableStatus.PARTIALLY_PAID);
            assertThat(r.outstanding()).isEqualByComparingTo("700");
            assertThat(result.transfer().unallocatedAmount()).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("no amount named and the receivable is the smaller: it is paid in full, the rest stays credit")
        void defaultAmountSettlesTheReceivable() {
            CustomerTransfer t = knownTransfer("1000");
            Receivable r = lockedReceivable("250.50");

            AllocationResult result = service.allocate(new AllocateCommand(t.id(), null, r.id(), null, by));

            assertThat(r.status()).isEqualTo(ReceivableStatus.PAID);
            assertThat(r.settledAt()).isEqualTo(NOW);
            assertThat(result.transfer().unallocatedAmount()).isEqualByComparingTo("749.50");
            ArgumentCaptor<PaymentCaptured> event = ArgumentCaptor.forClass(PaymentCaptured.class);
            verify(events).publishEvent(event.capture());
            assertThat(event.getValue().amount()).isEqualByComparingTo("250.50");
            assertThat(event.getValue().method()).isEqualTo(PaymentMethod.BANK_TRANSFER);
            assertThat(event.getValue().source()).isEqualTo("RECEIVABLE_ALLOCATION");
        }

        @Test
        @DisplayName("a named amount above the receivable's outstanding is ALLOCATION_EXCEEDS_AMOUNT_DUE")
        void aboveOutstanding() {
            CustomerTransfer t = knownTransfer("1000");
            Receivable r = lockedReceivable("400");
            assertThat(codeOf(() -> service.allocate(
                    new AllocateCommand(t.id(), null, r.id(), new BigDecimal("400.01"), by))))
                    .isEqualTo(ErrorCode.ALLOCATION_EXCEEDS_AMOUNT_DUE);
            assertThat(r.paidAmount()).isEqualByComparingTo("0");
            assertThat(t.unallocatedAmount()).isEqualByComparingTo("1000");
        }

        @Test
        @DisplayName("an exhausted transfer (no credit) allocates nothing: amount 0 is ALLOCATION_EXCEEDS_AMOUNT_DUE")
        void exhaustedTransfer() {
            CustomerTransfer t = knownTransfer("100");
            t.takeExactly(new BigDecimal("100"));
            Receivable r = lockedReceivable("400");
            assertThat(codeOf(() -> service.allocate(new AllocateCommand(t.id(), null, r.id(), null, by))))
                    .isEqualTo(ErrorCode.ALLOCATION_EXCEEDS_AMOUNT_DUE);
        }

        @Test
        @DisplayName("a named amount above what the transfer holds is ALLOCATION_EXCEEDS_TRANSFER")
        void aboveTransfer() {
            CustomerTransfer t = knownTransfer("100");
            Receivable r = lockedReceivable("400");
            assertThat(codeOf(() -> service.allocate(
                    new AllocateCommand(t.id(), null, r.id(), new BigDecimal("101"), by))))
                    .isEqualTo(ErrorCode.ALLOCATION_EXCEEDS_TRANSFER);
            assertThat(r.paidAmount()).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("a closure-fee receivable is announced as FEE_RECEIVABLE_ALLOCATION")
        void feeReceivableSource() {
            CustomerTransfer t = knownTransfer("100");
            Receivable fee = Receivable.openCancellationFee(UUID.randomUUID(), customer, new BigDecimal("100"), "VND",
                    NOW, TODAY).forShipment(UUID.randomUUID());
            when(book.lockReceivable(fee.id())).thenReturn(Optional.of(fee));
            when(book.recordAllocation(any(), any(), any(), any(), any(), any()))
                    .thenReturn(allocation(t.id(), fee.orderId(), fee.id(), "100"));

            service.allocate(new AllocateCommand(t.id(), null, fee.id(), null, by));

            ArgumentCaptor<PaymentCaptured> event = ArgumentCaptor.forClass(PaymentCaptured.class);
            verify(events).publishEvent(event.capture());
            assertThat(event.getValue().source()).isEqualTo("FEE_RECEIVABLE_ALLOCATION");
        }
    }

    // ------------------------------------------------------------------ reverse

    @Nested
    class Reverse {

        private final UUID orderId = UUID.randomUUID();

        private ReceivableBook.Allocation original(CustomerTransfer t, String amount) {
            ReceivableBook.Allocation a = allocation(t.id(), orderId, null, amount);
            when(book.findAllocation(a.id())).thenReturn(Optional.of(a));
            return a;
        }

        private PayableOrder lockOrder(String status, String term, BigDecimal threshold) {
            PayableOrder o = new PayableOrder(orderId, "SO-9", customer, status, term, null, "VND",
                    new BigDecimal("1000"), null, BigDecimal.ZERO, threshold, true);
            when(orders.lockForPayment(orderId)).thenReturn(Optional.of(o));
            return o;
        }

        private ReverseCommand command(CustomerTransfer t, ReceivableBook.Allocation a, boolean hold) {
            return new ReverseCommand(t.id(), a.id(), "  wrong order  ", hold, by);
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "   "})
        @DisplayName("a reversal says why (BR-06): a blank or missing reason is VALIDATION_FAILED")
        void needsReason(String reason) {
            assertThat(codeOf(() -> service.reverse(
                    new ReverseCommand(UUID.randomUUID(), UUID.randomUUID(), reason, false, by))))
                    .isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThat(codeOf(() -> service.reverse(
                    new ReverseCommand(UUID.randomUUID(), UUID.randomUUID(), null, false, by))))
                    .isEqualTo(ErrorCode.VALIDATION_FAILED);
        }

        @Test
        @DisplayName("an unknown transfer is TRANSFER_NOT_FOUND")
        void unknownTransfer() {
            UUID missing = UUID.randomUUID();
            when(book.findTransfer(missing)).thenReturn(Optional.empty());
            assertThat(codeOf(() -> service.reverse(new ReverseCommand(missing, UUID.randomUUID(), "x", false, by))))
                    .isEqualTo(ErrorCode.TRANSFER_NOT_FOUND);
        }

        @Test
        @DisplayName("an allocation of another transfer (or none) is ALLOCATION_NOT_FOUND")
        void allocationOfAnotherTransfer() {
            CustomerTransfer t = knownTransfer("1000");
            ReceivableBook.Allocation foreign = allocation(UUID.randomUUID(), orderId, null, "100");
            when(book.findAllocation(foreign.id())).thenReturn(Optional.of(foreign));
            assertThat(codeOf(() -> service.reverse(command(t, foreign, false))))
                    .isEqualTo(ErrorCode.ALLOCATION_NOT_FOUND);
            UUID none = UUID.randomUUID();
            when(book.findAllocation(none)).thenReturn(Optional.empty());
            assertThat(codeOf(() -> service.reverse(new ReverseCommand(t.id(), none, "x", false, by))))
                    .isEqualTo(ErrorCode.ALLOCATION_NOT_FOUND);
        }

        @Test
        @DisplayName("a reversal row is not itself reversed")
        void reversalIsNotReversed() {
            CustomerTransfer t = knownTransfer("1000");
            ReceivableBook.Allocation reversal = new ReceivableBook.Allocation(UUID.randomUUID(), t.id(), orderId, null,
                    new BigDecimal("-100"), NOW, by, UUID.randomUUID(), "x");
            when(book.findAllocation(reversal.id())).thenReturn(Optional.of(reversal));
            assertThat(codeOf(() -> service.reverse(command(t, reversal, false))))
                    .isEqualTo(ErrorCode.ALLOCATION_ALREADY_REVERSED);
        }

        @Test
        @DisplayName("an allocation already reversed is ALLOCATION_ALREADY_REVERSED (checked under the order's lock)")
        void alreadyReversed() {
            CustomerTransfer t = knownTransfer("1000");
            ReceivableBook.Allocation a = original(t, "100");
            lockOrder("CONFIRMED", "PREPAID", null);
            when(book.isReversed(a.id())).thenReturn(true);
            assertThat(codeOf(() -> service.reverse(command(t, a, false))))
                    .isEqualTo(ErrorCode.ALLOCATION_ALREADY_REVERSED);
            verify(book, never()).recordReversal(any(), any(), any(), anyString());
        }

        @Test
        @DisplayName("money a refund was asked of cannot be taken back (PAYMENT_REVERSAL_NOT_ALLOWED)")
        void refundedMoney() {
            CustomerTransfer t = knownTransfer("1000");
            ReceivableBook.Allocation a = original(t, "100");
            lockOrder("CONFIRMED", "PREPAID", null);
            when(book.hasRefunds(a.id())).thenReturn(true);
            assertThat(codeOf(() -> service.reverse(command(t, a, false))))
                    .isEqualTo(ErrorCode.PAYMENT_REVERSAL_NOT_ALLOWED);
        }

        @ParameterizedTest
        @ValueSource(strings = {"CANCELLED", "COMPLETED", "CLOSED", "RETURN_REQUESTED", "RETURNED", "REFUNDED",
                "PARTIALLY_REFUNDED"})
        @DisplayName("history is not rewritten under a settled, cancelled or closed order")
        void settledOrder(String status) {
            CustomerTransfer t = knownTransfer("1000");
            ReceivableBook.Allocation a = original(t, "100");
            lockOrder(status, "PREPAID", null);
            assertThat(codeOf(() -> service.reverse(command(t, a, true))))
                    .isEqualTo(ErrorCode.PAYMENT_REVERSAL_NOT_ALLOWED);
        }

        @Test
        @DisplayName("a confirmed order left short of its terms needs the accountant's explicit hold "
                + "(PAYMENT_REVERSAL_NEEDS_HOLD); the order is never silently un-confirmed")
        void needsHold() {
            CustomerTransfer t = knownTransfer("1000");
            ReceivableBook.Allocation a = original(t, "300");
            lockOrder("CONFIRMED", "DEPOSIT", new BigDecimal("300"));
            when(book.collectedFor(orderId)).thenReturn(new BigDecimal("300"));

            assertThat(codeOf(() -> service.reverse(command(t, a, false))))
                    .isEqualTo(ErrorCode.PAYMENT_REVERSAL_NEEDS_HOLD);
            verify(book, never()).recordReversal(any(), any(), any(), anyString());
        }

        @Test
        @DisplayName("taking back money that keeps the order at or above its threshold needs no hold")
        void staysAboveThreshold() {
            CustomerTransfer t = knownTransfer("1000");
            t.takeExactly(new BigDecimal("500"));
            ReceivableBook.Allocation a = original(t, "200");
            lockOrder("CONFIRMED", "DEPOSIT", new BigDecimal("300"));
            when(book.collectedFor(orderId)).thenReturn(new BigDecimal("500"));
            ReceivableBook.Allocation reversal = allocation(t.id(), orderId, null, "-200");
            when(book.recordReversal(any(), any(), any(), anyString())).thenReturn(reversal);

            AllocationResult result = service.reverse(command(t, a, false));

            assertThat(result.allocation()).isSameAs(reversal);
            assertThat(t.unallocatedAmount()).isEqualByComparingTo("700");
        }

        @Test
        @DisplayName("holdOrder agreed: the reversal row is written with the trimmed reason, the money is credit again "
                + "and the order hears PaymentReversed")
        void reversesWithHold() {
            CustomerTransfer t = knownTransfer("1000");
            t.takeExactly(new BigDecimal("1000"));
            ReceivableBook.Allocation a = original(t, "1000");
            lockOrder("CONFIRMED", "PREPAID", new BigDecimal("1000"));
            when(book.collectedFor(orderId)).thenReturn(new BigDecimal("1000"));
            ReceivableBook.Allocation reversal = allocation(t.id(), orderId, null, "-1000");
            when(book.recordReversal(a, NOW, by, "wrong order")).thenReturn(reversal);

            AllocationResult result = service.reverse(command(t, a, true));

            assertThat(result.transfer().unallocatedAmount()).isEqualByComparingTo("1000");
            ArgumentCaptor<PaymentReversed> event = ArgumentCaptor.forClass(PaymentReversed.class);
            verify(events).publishEvent(event.capture());
            assertThat(event.getValue().orderId()).isEqualTo(orderId);
            assertThat(event.getValue().allocationId()).isEqualTo(a.id());
            assertThat(event.getValue().reversalId()).isEqualTo(reversal.id());
            assertThat(event.getValue().amount()).isEqualByComparingTo("1000");
            assertThat(event.getValue().source()).isEqualTo("ORDER_ALLOCATION");
            assertThat(event.getValue().reversedBy()).isEqualTo(by);
            assertThat(event.getValue().reversedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("an order that is not confirmed (no threshold, e.g. pending) is reversed without a hold")
        void noThresholdNoHold() {
            CustomerTransfer t = knownTransfer("1000");
            t.takeExactly(new BigDecimal("100"));
            ReceivableBook.Allocation a = original(t, "100");
            lockOrder("PENDING_PAYMENT", "PREPAID", null);
            when(book.recordReversal(any(), any(), any(), anyString())).thenReturn(allocation(t.id(), orderId, null, "-100"));
            service.reverse(command(t, a, false));
            verify(events).publishEvent(any(PaymentReversed.class));
        }

        @Test
        @DisplayName("a lost race on the unique reversal row is ALLOCATION_ALREADY_REVERSED, not a 500")
        void racedReversal() {
            CustomerTransfer t = knownTransfer("1000");
            t.takeExactly(new BigDecimal("100"));
            ReceivableBook.Allocation a = original(t, "100");
            lockOrder("PENDING_PAYMENT", "PREPAID", null);
            when(book.recordReversal(any(), any(), any(), anyString())).thenThrow(new DataIntegrityViolationException("dup"));
            assertThat(codeOf(() -> service.reverse(command(t, a, false))))
                    .isEqualTo(ErrorCode.ALLOCATION_ALREADY_REVERSED);
            verify(events, never()).publishEvent(any(Object.class));
        }

        @Test
        @DisplayName("reversing a receivable payment reopens the receivable (status follows the money and due date) and "
                + "announces the receivable's source")
        void reversesReceivablePayment() {
            CustomerTransfer t = knownTransfer("1000");
            t.takeExactly(new BigDecimal("400"));
            Receivable r = receivable(customer, "400", TODAY.plusDays(3));
            r.allocate(new BigDecimal("400"), NOW);
            ReceivableBook.Allocation a = new ReceivableBook.Allocation(UUID.randomUUID(), t.id(), r.orderId(), r.id(),
                    new BigDecimal("400"), NOW, by, null, null);
            when(book.findAllocation(a.id())).thenReturn(Optional.of(a));
            PayableOrder o = new PayableOrder(r.orderId(), "SO-C", customer, "DELIVERED", "CREDIT", null, "VND",
                    new BigDecimal("400"), null, BigDecimal.ZERO, null, false);
            when(orders.lockForPayment(r.orderId())).thenReturn(Optional.of(o));
            when(book.lockReceivable(r.id())).thenReturn(Optional.of(r));
            when(book.recordReversal(any(), any(), any(), anyString())).thenReturn(allocation(t.id(), r.orderId(), r.id(), "-400"));

            service.reverse(command(t, a, false));

            assertThat(r.status()).isEqualTo(ReceivableStatus.OPEN);
            assertThat(r.paidAmount()).isEqualByComparingTo("0");
            assertThat(r.settledAt()).isNull();
            verify(book).save(r);
            ArgumentCaptor<PaymentReversed> event = ArgumentCaptor.forClass(PaymentReversed.class);
            verify(events).publishEvent(event.capture());
            assertThat(event.getValue().source()).isEqualTo("RECEIVABLE_ALLOCATION");
        }
    }

    // ------------------------------------------------------------------ refundCredit

    @Nested
    class RefundCredit {

        @ParameterizedTest
        @ValueSource(strings = {"0", "-1"})
        @DisplayName("a credit refund names a positive amount and a reason")
        void validation(String amount) {
            assertThat(codeOf(() -> service.refundCredit(
                    new CreditRefundCommand(UUID.randomUUID(), new BigDecimal(amount), "why", by))))
                    .isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThat(codeOf(() -> service.refundCredit(
                    new CreditRefundCommand(UUID.randomUUID(), BigDecimal.TEN, "  ", by))))
                    .isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThat(codeOf(() -> service.refundCredit(
                    new CreditRefundCommand(UUID.randomUUID(), null, "why", by))))
                    .isEqualTo(ErrorCode.VALIDATION_FAILED);
            verifyNoInteractions(book);
        }

        @Test
        @DisplayName("an unknown transfer is TRANSFER_NOT_FOUND")
        void unknown() {
            UUID missing = UUID.randomUUID();
            when(book.lockTransfer(missing)).thenReturn(Optional.empty());
            assertThat(codeOf(() -> service.refundCredit(new CreditRefundCommand(missing, BigDecimal.TEN, "x", by))))
                    .isEqualTo(ErrorCode.TRANSFER_NOT_FOUND);
        }

        @Test
        @DisplayName("part of the credit goes out as a PENDING refund with the trimmed reason; the rest stays credit")
        void refundsPartOfTheCredit() {
            CustomerTransfer t = knownTransfer("1000");
            UUID refundId = UUID.randomUUID();
            when(book.recordCreditRefund(any(), any(), anyString())).thenReturn(refundId);

            var result = service.refundCredit(new CreditRefundCommand(t.id(), new BigDecimal("250"), " over-paid ", by));

            assertThat(result.refundId()).isEqualTo(refundId);
            assertThat(t.unallocatedAmount()).isEqualByComparingTo("750");
            assertThat(t.refundedAmount()).isEqualByComparingTo("250");
            verify(book).recordCreditRefund(t, new BigDecimal("250"), "over-paid");
        }

        @Test
        @DisplayName("BR-05: never more than the transfer still holds, and nothing is recorded")
        void neverMoreThanTheCredit() {
            CustomerTransfer t = knownTransfer("1000");
            t.takeExactly(new BigDecimal("900"));
            assertThat(codeOf(() -> service.refundCredit(
                    new CreditRefundCommand(t.id(), new BigDecimal("101"), "x", by))))
                    .isEqualTo(ErrorCode.ALLOCATION_EXCEEDS_TRANSFER);
            verify(book, never()).recordCreditRefund(any(), any(), anyString());
        }
    }

    // ------------------------------------------------------------------ carrier remittance

    @Nested
    class CarrierRemittance {

        private Receivable cod(String amount) {
            Receivable r = Receivable.openCarrierCollected(UUID.randomUUID(), customer, new BigDecimal(amount), "VND",
                    NOW, TODAY);
            when(book.lockReceivable(r.id())).thenReturn(Optional.of(r));
            when(orders.find(r.orderId())).thenReturn(Optional.of(order(r.orderId(), customer, "DELIVERED", "DEPOSIT", amount)));
            when(book.recordAllocation(any(), any(), any(), any(), any(), any())).thenAnswer(call ->
                    new ReceivableBook.Allocation(UUID.randomUUID(), call.getArgument(0), call.getArgument(1),
                            call.getArgument(2), call.getArgument(3), NOW, by, null, null));
            return r;
        }

        private CarrierRemittanceCommand remit(Receivable r, String amount) {
            return new CarrierRemittanceCommand(r.id(), "GHN", "GHN-0910", new BigDecimal(amount), TODAY, null, by);
        }

        @Test
        @DisplayName("an unknown receivable is RECEIVABLE_NOT_FOUND")
        void unknown() {
            UUID missing = UUID.randomUUID();
            when(book.lockReceivable(missing)).thenReturn(Optional.empty());
            assertThat(codeOf(() -> service.recordCarrierRemittance(new CarrierRemittanceCommand(missing, "GHN", "R",
                    BigDecimal.TEN, TODAY, null, by)))).isEqualTo(ErrorCode.RECEIVABLE_NOT_FOUND);
        }

        @Test
        @DisplayName("a receivable the customer pays themselves takes no carrier remittance")
        void notCarrierCollected() {
            Receivable r = receivable(customer, "100", TODAY);
            when(book.lockReceivable(r.id())).thenReturn(Optional.of(r));
            assertThat(codeOf(() -> service.recordCarrierRemittance(remit(r, "100"))))
                    .isEqualTo(ErrorCode.RECEIVABLE_NOT_CARRIER_COLLECTED);
        }

        @Test
        @DisplayName("a receivable already paid in full is RECEIVABLE_SETTLED")
        void settled() {
            Receivable r = cod("100");
            r.allocate(new BigDecimal("100"), NOW);
            assertThat(codeOf(() -> service.recordCarrierRemittance(remit(r, "100"))))
                    .isEqualTo(ErrorCode.RECEIVABLE_SETTLED);
        }

        @Test
        @DisplayName("BR-07: the same statement line for the same order is recorded once")
        void referenceRecordedTwice() {
            Receivable r = cod("100");
            when(book.referenceRecorded(anyString())).thenReturn(true);
            assertThat(codeOf(() -> service.recordCarrierRemittance(remit(r, "100"))))
                    .isEqualTo(ErrorCode.TRANSFER_REFERENCE_ALREADY_RECORDED);
            verify(book, never()).save(any(CustomerTransfer.class));
        }

        @Test
        @DisplayName("BR-07: a unique-constraint race on the reference is TRANSFER_REFERENCE_ALREADY_RECORDED")
        void referenceRace() {
            Receivable r = cod("100");
            when(book.save(any(CustomerTransfer.class))).thenThrow(new DataIntegrityViolationException("uk"));
            assertThat(codeOf(() -> service.recordCarrierRemittance(remit(r, "100"))))
                    .isEqualTo(ErrorCode.TRANSFER_REFERENCE_ALREADY_RECORDED);
        }

        @Test
        @DisplayName("an exact remittance pays the carrier-collected balance in full, announced as COD")
        void exactRemittance() {
            Receivable r = cod("770000");

            AllocationResult result = service.recordCarrierRemittance(remit(r, "770000"));

            assertThat(r.status()).isEqualTo(ReceivableStatus.PAID);
            assertThat(result.transfer().channel()).isEqualTo(TransferChannel.CARRIER_REMITTANCE);
            assertThat(result.transfer().carrierName()).isEqualTo("GHN");
            assertThat(result.transfer().unallocatedAmount()).isEqualByComparingTo("0");
            assertThat(result.allocation().amount()).isEqualByComparingTo("770000");
            ArgumentCaptor<PaymentCaptured> event = ArgumentCaptor.forClass(PaymentCaptured.class);
            verify(events).publishEvent(event.capture());
            assertThat(event.getValue().method()).isEqualTo(PaymentMethod.COD);
        }

        @Test
        @DisplayName("a short remittance leaves the receivable PARTIALLY_PAID: the carrier's debt (09 edge)")
        void shortRemittance() {
            Receivable r = cod("770000");

            AllocationResult result = service.recordCarrierRemittance(remit(r, "700000"));

            assertThat(r.status()).isEqualTo(ReceivableStatus.PARTIALLY_PAID);
            assertThat(r.outstanding()).isEqualByComparingTo("70000");
            assertThat(result.allocation().amount()).isEqualByComparingTo("700000");
        }

        @Test
        @DisplayName("an over-remittance pays the balance and the excess stays as the customer's credit")
        void overRemittance() {
            Receivable r = cod("770000");

            AllocationResult result = service.recordCarrierRemittance(remit(r, "800000"));

            assertThat(r.status()).isEqualTo(ReceivableStatus.PAID);
            assertThat(result.allocation().amount()).isEqualByComparingTo("770000");
            assertThat(result.transfer().unallocatedAmount()).isEqualByComparingTo("30000");
        }

        @Test
        @DisplayName("the reference names the statement line, the order number and, for a shipment's balance, "
                + "the last 12 hex digits of the shipment id")
        void reference() {
            Receivable whole = receivable(customer, "10", TODAY);
            assertThat(MoneyLedgerService.remittanceReference("  GHN-1 ", "SO-7", whole)).isEqualTo("GHN-1:SO-7");

            UUID shipment = UUID.fromString("018f3a52-7c1d-7abc-8def-0123456789ab");
            Receivable split = receivable(customer, "10", TODAY).forShipment(shipment);
            assertThat(MoneyLedgerService.remittanceReference("GHN-1", "SO-7", split))
                    .isEqualTo("GHN-1:SO-7:0123456789ab");

            assertThatThrownBy(() -> MoneyLedgerService.remittanceReference(null, "SO-7", whole))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("an order that cannot be found falls back to its id in the reference")
        void referenceFallsBackToOrderId() {
            Receivable r = cod("100");
            when(orders.find(r.orderId())).thenReturn(Optional.empty());
            service.recordCarrierRemittance(remit(r, "100"));
            verify(book).referenceRecorded("GHN-0910:" + r.orderId());
        }
    }

    // ------------------------------------------------------------------ reads

    @Nested
    class Reads {

        @Test
        @DisplayName("an unknown order's payments are NOT_FOUND")
        void unknownOrder() {
            UUID missing = UUID.randomUUID();
            when(orders.find(missing)).thenReturn(Optional.empty());
            assertThat(codeOf(() -> service.orderPayments(missing))).isEqualTo(ErrorCode.NOT_FOUND);
        }

        @Test
        @DisplayName("transferDetail: the transfer with its allocations; unknown is TRANSFER_NOT_FOUND")
        void transferDetail() {
            CustomerTransfer t = knownTransfer("100");
            List<ReceivableBook.Allocation> made = List.of(allocation(t.id(), UUID.randomUUID(), null, "10"));
            when(book.allocationsOfTransfer(t.id())).thenReturn(made);
            assertThat(service.transferDetail(t.id()).allocations()).isEqualTo(made);

            UUID missing = UUID.randomUUID();
            when(book.findTransfer(missing)).thenReturn(Optional.empty());
            assertThat(codeOf(() -> service.transferDetail(missing))).isEqualTo(ErrorCode.TRANSFER_NOT_FOUND);
        }

        @Test
        @DisplayName("an order's payments: collected, receivables outstanding and what a transfer may still take")
        void orderPayments() {
            UUID orderId = UUID.randomUUID();
            CustomerTransfer t = knownTransfer("1000");
            when(orders.find(orderId)).thenReturn(Optional.of(order(orderId, customer, "CONFIRMED", "PREPAID", "1000")));
            when(book.allocationsOfOrder(orderId)).thenReturn(List.of(allocation(t.id(), orderId, null, "400"),
                    allocation(t.id(), orderId, null, "100")));
            when(book.collectedFor(orderId)).thenReturn(new BigDecimal("500"));
            when(book.outstandingReceivablesOf(orderId)).thenReturn(BigDecimal.ZERO);

            var payments = service.orderPayments(orderId);

            assertThat(payments.collected()).isEqualByComparingTo("500");
            assertThat(payments.mayStillTake()).isEqualByComparingTo("500");
            assertThat(payments.transfers()).as("one entry per transfer").hasSize(1);
        }

        @ParameterizedTest
        @ValueSource(strings = {"CANCELLED", "CREDIT"})
        @DisplayName("a cancelled or credit order may take nothing by transfer")
        void noRoomForCancelledOrCredit(String which) {
            UUID orderId = UUID.randomUUID();
            PayableOrder o = "CREDIT".equals(which) ? order(orderId, customer, "DELIVERED", "CREDIT", "1000")
                    : order(orderId, customer, "CANCELLED", "PREPAID", "1000");
            when(orders.find(orderId)).thenReturn(Optional.of(o));
            when(book.allocationsOfOrder(orderId)).thenReturn(List.of());
            when(book.collectedFor(orderId)).thenReturn(BigDecimal.ZERO);
            when(book.outstandingReceivablesOf(orderId)).thenReturn(BigDecimal.ZERO);
            assertThat(service.orderPayments(orderId).mayStillTake()).isEqualByComparingTo("0");
        }
    }
}
