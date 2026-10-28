package com.stockflow.order.internal.service;

import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.design.api.DesignService;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.api.ScrapCancelledPrintCommand;
import com.stockflow.order.api.CancelOrderCommand;
import com.stockflow.order.api.CancellationReasonCode;
import com.stockflow.order.api.HoldReason;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.PaymentTerm;
import com.stockflow.order.internal.domain.CancellationRequest;
import com.stockflow.order.internal.domain.CancellationRequestRepository;
import com.stockflow.order.internal.domain.CreditChecks;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderNumber;
import com.stockflow.order.internal.domain.OrderPayments;
import com.stockflow.order.internal.domain.OrderRepository;
import com.stockflow.order.internal.repository.OrderHoldJpaRepository;
import com.stockflow.order.internal.repository.OrderSearchRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.stockflow.order.internal.service.OrderFixtures.NOW;
import static com.stockflow.order.internal.service.OrderFixtures.STAFF;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What {@link OrderServiceImpl} does when other modules tell it something: money counted or taken back (kltn-docs 15
 * BR-02, BR-06), a failed payment or an expired stock hold (14 BR-07), and what any cancellation does with the order's
 * holds (17 §4.4, BR-PRD-10). {@code placeOrder} is deliberately not covered here.
 */
class OrderServiceImplReactionsTest {

    private final OrderRepository repository = mock(OrderRepository.class);
    private final InventoryService inventory = mock(InventoryService.class);
    private final OrderEventPublisher events = mock(OrderEventPublisher.class);
    private final OrderPayments payments = mock(OrderPayments.class);
    private final CancellationRequestRepository requests = mock(CancellationRequestRepository.class);
    private final OrderServiceImpl service = new OrderServiceImpl(repository, mock(OrderSearchRepository.class),
            inventory, events, Clock.fixed(NOW, ZoneOffset.UTC), mock(DesignService.class),
            mock(OrderHoldJpaRepository.class), mock(CustomerService.class),
            mock(com.stockflow.catalog.api.CatalogService.class), payments, requests, mock(CreditChecks.class),
            mock(com.stockflow.payment.api.PaymentService.class));

    private Order found(Order order) {
        when(repository.findByIdForUpdate(order.id())).thenReturn(Optional.of(order));
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));
        return order;
    }

    private Order waitingForPayment(PaymentTerm term) {
        Order order = OrderFixtures.draft(term, UUID.randomUUID());
        order.submit();
        order.pullDomainEvents();
        return found(order);
    }

    private UUID id(Order order) {
        return order.id().value();
    }

    @Nested
    @DisplayName("money counted (PaymentCaptured)")
    class PaymentCaptured {

        @Test
        @DisplayName("a prepaid order paid in full is CONFIRMED and its stock holds are pinned (BR-02, BR-07)")
        void fullPaymentConfirmsAndPins() {
            Order order = waitingForPayment(PaymentTerm.PREPAID);
            when(payments.record(any(), any(), any(), anyString(), any())).thenReturn(true);

            service.recordPayment(id(order), UUID.randomUUID(), order.total().amount(), "VND");

            assertThat(order.status()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(order.paidAmount()).isEqualByComparingTo(order.total().amount());
            verify(inventory).pinReservations(id(order));
            verify(repository).save(order);
            verify(events).publishEventsOf(order);
        }

        @Test
        @DisplayName("part of the price does not confirm a prepaid order and pins nothing")
        void partialPaymentWaits() {
            Order order = waitingForPayment(PaymentTerm.PREPAID);
            when(payments.record(any(), any(), any(), anyString(), any())).thenReturn(true);

            service.recordPayment(id(order), UUID.randomUUID(), new BigDecimal("100000"), "VND");

            assertThat(order.status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
            assertThat(order.paidAmount()).isEqualByComparingTo("100000");
            verify(inventory, never()).pinReservations(any());
        }

        @Test
        @DisplayName("a deposit order is CONFIRMED by its deposit (30 percent of 1 100 000), not the whole price")
        void depositConfirms() {
            Order order = waitingForPayment(PaymentTerm.DEPOSIT);
            when(payments.record(any(), any(), any(), anyString(), any())).thenReturn(true);

            service.recordPayment(id(order), UUID.randomUUID(), new BigDecimal("330000"), "VND");

            assertThat(order.status()).isEqualTo(OrderStatus.CONFIRMED);
            verify(inventory).pinReservations(id(order));
        }

        @Test
        @DisplayName("a redelivered event is counted once: the order is not touched")
        void redeliveryIsIgnored() {
            Order order = waitingForPayment(PaymentTerm.PREPAID);
            when(payments.record(any(), any(), any(), anyString(), any())).thenReturn(false);

            service.recordPayment(id(order), UUID.randomUUID(), order.total().amount(), "VND");

            assertThat(order.status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
            verify(repository, never()).save(any());
            verify(inventory, never()).pinReservations(any());
        }

        @Test
        @DisplayName("money in another currency is not added up, and not retried")
        void foreignCurrencyIsNotCounted() {
            Order order = waitingForPayment(PaymentTerm.PREPAID);

            service.recordPayment(id(order), UUID.randomUUID(), order.total().amount(), "USD");

            assertThat(order.paidAmount()).isEqualByComparingTo("0");
            verify(payments, never()).record(any(), any(), any(), anyString(), any());
            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("money for a cancelled order is counted (and logged for Sales), the order stays cancelled")
        void moneyForACancelledOrderIsKept() {
            Order order = waitingForPayment(PaymentTerm.PREPAID);
            order.cancel(CancellationReasonCode.PAYMENT_NOT_RECEIVED, null, null, null, NOW);
            when(payments.record(any(), any(), any(), anyString(), any())).thenReturn(true);

            service.recordPayment(id(order), UUID.randomUUID(), new BigDecimal("500000"), "VND");

            assertThat(order.status()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(order.paidAmount()).isEqualByComparingTo("500000");
            verify(inventory, never()).pinReservations(any());
            verify(repository).save(order);
        }

        @Test
        @DisplayName("an unknown order is an IllegalArgumentException")
        void unknownOrder() {
            when(repository.findByIdForUpdate(any())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.recordPayment(UUID.randomUUID(), UUID.randomUUID(), BigDecimal.TEN, "VND"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("money taken back (PaymentReversed, BR-06)")
    class PaymentReversed {

        private Order confirmed() {
            Order order = waitingForPayment(PaymentTerm.PREPAID);
            order.recordPayment(order.total(), NOW);
            order.pullDomainEvents();
            return order;
        }

        @Test
        @DisplayName("a reversal that leaves a confirmed order short puts it ON_HOLD with reason PAYMENT")
        void reversalHoldsTheOrder() {
            Order order = confirmed();
            when(payments.record(any(), any(), any(), anyString(), any())).thenReturn(true);

            service.reversePayment(id(order), UUID.randomUUID(), new BigDecimal("200000"), "VND", false,
                    "bank recall", STAFF, NOW);

            assertThat(order.status()).isEqualTo(OrderStatus.ON_HOLD);
            assertThat(order.holdReason()).isEqualTo(HoldReason.PAYMENT);
            assertThat(order.paidAmount()).isEqualByComparingTo("900000");
            ArgumentCaptor<BigDecimal> amount = ArgumentCaptor.forClass(BigDecimal.class);
            verify(payments).record(any(), any(), amount.capture(), anyString(), any());
            assertThat(amount.getValue()).as("a reversal is a negative payment row").isEqualByComparingTo("-200000");
            verify(repository).save(order);
        }

        @Test
        @DisplayName("a redelivered reversal is counted once")
        void redeliveryIsIgnored() {
            Order order = confirmed();
            when(payments.record(any(), any(), any(), anyString(), any())).thenReturn(false);

            service.reversePayment(id(order), UUID.randomUUID(), new BigDecimal("200000"), "VND", false, "x", STAFF,
                    NOW);

            assertThat(order.paidAmount()).isEqualByComparingTo("1100000");
            assertThat(order.status()).isEqualTo(OrderStatus.CONFIRMED);
            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("a reversal in another currency is not counted")
        void foreignCurrency() {
            Order order = confirmed();

            service.reversePayment(id(order), UUID.randomUUID(), new BigDecimal("200000"), "USD", false, "x", STAFF,
                    NOW);

            assertThat(order.paidAmount()).isEqualByComparingTo("1100000");
            verify(payments, never()).record(any(), any(), any(), anyString(), any());
        }
    }

    @Nested
    @DisplayName("a failed payment cancels the order (PaymentFailed)")
    class PaymentFailed {

        @Test
        @DisplayName("an order waiting for payment is cancelled with PAYMENT_FAILED and the failure code as its note")
        void cancels() {
            Order order = waitingForPayment(PaymentTerm.PREPAID);

            service.cancelAfterPaymentFailure(id(order), "CARD_DECLINED");

            assertThat(order.status()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(order.cancellationReasonCode()).isEqualTo(CancellationReasonCode.PAYMENT_FAILED);
            assertThat(order.cancellationReason()).isEqualTo("PAYMENT_FAILED: CARD_DECLINED");
            assertThat(order.reservationIds()).as("inventory releases its own holds on the same event").isEmpty();
            verify(repository).save(order);
            verify(events).publishEventsOf(order);
            verify(inventory, never()).release(any(), anyString());
        }

        @Test
        @DisplayName("a redelivered event for an order already cancelled does nothing")
        void redeliveryIsIgnored() {
            Order order = waitingForPayment(PaymentTerm.PREPAID);
            service.cancelAfterPaymentFailure(id(order), "X");
            org.mockito.Mockito.clearInvocations(repository, events);

            service.cancelAfterPaymentFailure(id(order), "X");

            verify(repository, never()).save(any());
            verify(events, never()).publishEventsOf(any());
        }

        @Test
        @DisplayName("an order that can no longer be cancelled is left for Sales; the listener does not throw")
        void aDeliveredOrderIsLeftAlone() {
            Order order = found(OrderFixtures.delivered(NOW));

            service.cancelAfterPaymentFailure(id(order), "X");

            assertThat(order.status()).isEqualTo(OrderStatus.DELIVERED);
            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("an unknown order is an IllegalArgumentException")
        void unknownOrder() {
            when(repository.findByIdForUpdate(any())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.cancelAfterPaymentFailure(UUID.randomUUID(), "X"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("a pending cancellation request of the cancelled order is closed as EXPIRED")
        void pendingRequestIsClosed() {
            Order order = waitingForPayment(PaymentTerm.PREPAID);
            CancellationRequest pending = CancellationRequest.open(id(order), UUID.randomUUID(),
                    CancellationReasonCode.CUSTOMER_REQUEST, null, NOW);
            when(requests.findPending(id(order))).thenReturn(Optional.of(pending));

            service.cancelAfterPaymentFailure(id(order), "X");

            verify(requests).save(pending);
            assertThat(pending.decisionNote()).isEqualTo("Closed: the order was cancelled.");
        }
    }

    @Nested
    @DisplayName("a stock hold expired before payment (14 BR-07)")
    class HoldExpired {

        @Test
        @DisplayName("an unpaid order is cancelled with PAYMENT_NOT_RECEIVED and its other holds are released")
        void cancelsTheUnpaidOrder() {
            Order order = waitingForPayment(PaymentTerm.PREPAID);
            List<UUID> holds = order.reservationIds();

            service.cancelAfterHoldExpired(id(order));

            assertThat(order.status()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(order.cancellationReasonCode()).isEqualTo(CancellationReasonCode.PAYMENT_NOT_RECEIVED);
            holds.forEach(hold -> verify(inventory).release(hold, "ORDER_CANCELLED"));
            assertThat(order.reservationIds()).isEmpty();
        }

        @Test
        @DisplayName("an order no longer waiting for payment is left alone: its holds are pinned, the event is late")
        void aPaidOrderIsLeftAlone() {
            Order order = found(OrderFixtures.confirmedPrepaid());

            service.cancelAfterHoldExpired(id(order));

            assertThat(order.status()).isEqualTo(OrderStatus.CONFIRMED);
            verify(repository, never()).save(any());
            verify(inventory, never()).release(any(), anyString());
        }

        @Test
        @DisplayName("an unknown order is ignored")
        void unknownOrder() {
            when(repository.findByIdForUpdate(any())).thenReturn(Optional.empty());

            service.cancelAfterHoldExpired(UUID.randomUUID());

            verify(repository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("what cancelling does with the holds")
    class Cancelling {

        @Test
        @DisplayName("every reservation of a cancelled order is released as ORDER_CANCELLED")
        void releasesEveryHold() {
            Order order = waitingForPayment(PaymentTerm.PREPAID);
            List<UUID> holds = order.reservationIds();
            assertThat(holds).hasSize(2);

            service.cancelLoaded(order, new CancelOrderCommand(CancellationReasonCode.CUSTOMER_REQUEST, null, null,
                    STAFF));

            holds.forEach(hold -> verify(inventory).release(hold, "ORDER_CANCELLED"));
            verify(inventory, never()).scrapCancelledPrint(any());
            assertThat(order.reservationIds()).isEmpty();
            verify(events).publishEventsOf(order);
        }

        @Test
        @DisplayName("an unprinted print line is scrapped for its open units, by whoever cancelled (BR-PRD-10)")
        void scrapsPrintedUnits() {
            UUID design = UUID.randomUUID();
            Order order = Order.draft(OrderNumber.of(LocalDate.of(2026, 10, 27), 9), UUID.randomUUID(),
                    UUID.randomUUID(), List.of(Order.line(new Sku("CUP-12OZ"), 500, Money.vnd(2_000), design)), NOW);
            order.applyTerms(PaymentTerm.PREPAID, null);
            order.attachReservations(order.lines().get(0).id(), List.of(UUID.randomUUID()));
            order.submit();
            found(order);

            service.cancelLoaded(order, new CancelOrderCommand(CancellationReasonCode.CUSTOMER_REQUEST, null, null,
                    STAFF));

            ArgumentCaptor<ScrapCancelledPrintCommand> scrap = ArgumentCaptor.forClass(ScrapCancelledPrintCommand.class);
            verify(inventory).scrapCancelledPrint(scrap.capture());
            assertThat(scrap.getValue().orderId()).isEqualTo(id(order));
            assertThat(scrap.getValue().quantity()).isEqualTo(500);
            assertThat(scrap.getValue().requestedBy()).isEqualTo(STAFF);
        }

        @Test
        @DisplayName("an order already shipped is refused: ORDER_NOT_CANCELLABLE, nothing released or saved")
        void aShippedOrderIsNotCancelled() {
            Order order = OrderFixtures.releasedPrepaid();
            order.markPacked(NOW, STAFF);
            order.markShipped(NOW, STAFF);

            assertThat(OrderFixtures.codeOf(() -> service.cancelLoaded(order,
                    new CancelOrderCommand(CancellationReasonCode.CUSTOMER_REQUEST, null, null, STAFF))))
                    .isEqualTo(ErrorCode.ORDER_NOT_CANCELLABLE);
            verify(inventory, never()).release(any(), anyString());
            verify(repository, never()).save(any());
        }
    }
}
