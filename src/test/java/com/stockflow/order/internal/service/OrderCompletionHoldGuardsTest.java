package com.stockflow.order.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.contracts.SalesContractStatusChanged;
import com.stockflow.order.api.DeliveryFailureReason;
import com.stockflow.order.api.DeliveryInvoices;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.ShipmentHandover;
import com.stockflow.order.internal.domain.CompletionHoldReason;
import com.stockflow.order.internal.domain.DeliveryFailureCases;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderRepository;
import com.stockflow.order.internal.domain.SalesContract;
import com.stockflow.order.internal.domain.SalesContractRepository;
import com.stockflow.order.internal.domain.SalesContractStatus;
import com.stockflow.order.internal.domain.SettlementCandidates;
import com.stockflow.order.internal.repository.OrderHistoryNotes;
import com.stockflow.payment.api.InstallmentStanding;
import com.stockflow.payment.api.PaymentService;
import com.stockflow.payment.api.RefundProgress;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.stockflow.order.internal.service.OrderFixtures.NOW;
import static com.stockflow.order.internal.service.OrderFixtures.STAFF;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WF-05 settlement guards on the services:
 * <ul>
 *   <li>Q-17 = a (BE-05, L-I3): a completion hold keeps the order from closing - also from the refund branch - and from
 *       PARTIALLY_FULFILLED → PARTIALLY_REFUNDED; a hold is placed only where there is a delivery to verify;</li>
 *   <li>Q-16 = b (BE-19, L-I5): the refund branch closes only once every delivered shipment's return window ended;</li>
 *   <li>Q-6 (BE-06, L-I4): a contract is COMPLETED only when goods were delivered, LIQUIDATED otherwise - at the close
 *       of a whole carrier return, and for an order cancelled after signing once its money is settled;</li>
 *   <li>Q-4 / BE-45 (audit r2 contract M-6): an order the carrier's returns ended with nothing made refundable is never
 *       PARTIALLY_REFUNDED at 0 đồng - it stays RETURNED / PARTIALLY_FULFILLED and closes from there;</li>
 *   <li>audit r2 lifecycle M-4: the hold is offered exactly where it is accepted ({@code completionHoldable}), and its
 *       release follows the refund branch on (L-1).</li>
 * </ul>
 */
class OrderCompletionHoldGuardsTest {

    private static final UUID S1 = UUID.randomUUID();
    private static final UUID S2 = UUID.randomUUID();

    private final OrderRepository repository = mock(OrderRepository.class);
    private final SettlementCandidates candidates = mock(SettlementCandidates.class);
    private final OrderEventPublisher events = mock(OrderEventPublisher.class);
    private final PaymentService payments = mock(PaymentService.class);
    private final SalesContractRepository contracts = mock(SalesContractRepository.class);
    private final DeliveryInvoices invoices = mock(DeliveryInvoices.class);

    {
        when(payments.installmentStanding(any())).thenAnswer(call -> InstallmentStanding.none(call.getArgument(0)));
        when(payments.refundProgress(any())).thenReturn(RefundProgress.NONE);
        when(invoices.invoiced(any())).thenAnswer(call -> call.getArgument(0) == null ? Set.of()
                : Set.copyOf(call.<java.util.Collection<UUID>>getArgument(0)));
    }

    private OrderSettlementService at(Instant now) {
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        return new OrderSettlementService(repository, candidates, events, payments, contracts,
                mock(DeliveryFailureService.class),
                new SettlementReadiness(candidates, payments, contracts, invoices, clock, 7, 30),
                mock(OrderHistoryNotes.class), clock, 7, 30);
    }

    private static Instant day(int days) {
        return NOW.plus(Duration.ofDays(days));
    }

    /** Cups on S1, lids on S2, handed over at NOW. */
    private static Order shipped() {
        Order order = OrderFixtures.released();
        order.decidePartialShipment(true);
        order.startPicking(STAFF, NOW);
        order.markPacked(NOW, STAFF);
        order.recordHandover(new ShipmentHandover(S1, List.of(new ShipmentHandover.Line(order.lines().get(0).id(), 10)),
                NOW, STAFF));
        order.recordHandover(new ShipmentHandover(S2, List.of(new ShipmentHandover.Line(order.lines().get(1).id(), 5)),
                NOW, STAFF));
        order.pullDomainEvents();
        order.pullTransitions();
        return order;
    }

    /** S1 delivered on day 1, S2 brought back and received on day 3, its refund recorded on day 4. */
    private static Order partlyRefunded() {
        Order order = shipped();
        order.recordShipmentDelivered(S1, day(1), STAFF);
        order.recordShipmentDeliveryFailed(S2, DeliveryFailureReason.REFUSED, null, day(1), STAFF);
        order.recordShipmentReturned(S2, day(2), STAFF);
        order.closeReturnedShipment(S2, day(3), day(3), STAFF);
        order.refunded(false, STAFF, day(4), "DELIVERY_FAILED_SETTLED");
        order.pullDomainEvents();
        return order;
    }

    private SalesContract signedContract(int windowDays) {
        SalesContract contract = mock(SalesContract.class);
        when(contract.status()).thenReturn(SalesContractStatus.SIGNED);
        when(contract.returnWindowDays()).thenReturn(windowDays);
        when(contract.complete(any())).thenReturn(true);
        when(contract.liquidate(any())).thenReturn(true);
        when(contract.contractNumber()).thenReturn("HD-1");
        return contract;
    }

    private void found(Order order) {
        when(repository.findByIdForUpdate(order.id())).thenReturn(Optional.of(order));
        when(repository.findById(order.id())).thenReturn(Optional.of(order));
    }

    @Test
    @DisplayName("Q-17: a held order is not closed, from the refund branch either; released, it closes")
    void holdKeepsTheOrderOpen() {
        Order order = partlyRefunded();
        found(order);
        UUID id = order.id().value();
        when(candidates.completionHeld(id)).thenReturn(true);

        assertThat(at(day(60)).close(id)).isFalse();
        assertThat(order.status()).isEqualTo(OrderStatus.PARTIALLY_REFUNDED);
        verify(repository, never()).save(any());

        when(candidates.completionHeld(id)).thenReturn(false);
        assertThat(at(day(60)).close(id)).isTrue();
        assertThat(order.status()).isEqualTo(OrderStatus.CLOSED);
    }

    @Test
    @DisplayName("Q-16 = b: from the refund branch the order closes only once the contract's return window of its "
            + "latest delivery ended - the closing delay alone is not enough")
    void refundBranchWaitsForTheReturnWindow() {
        Order order = partlyRefunded();
        found(order);
        UUID id = order.id().value();
        SalesContract contract = signedContract(60);
        when(contracts.findLiveByOrderId(id)).thenReturn(Optional.of(contract));

        assertThat(at(day(40)).close(id)).as("30 days after the refund, inside the 60-day window").isFalse();
        assertThat(at(day(63)).close(id)).isTrue();
        verify(contract).complete(any());
        verify(contract, never()).liquidate(any());
    }

    @Test
    @DisplayName("Q-6: a whole carrier return closes with its contract LIQUIDATED, announced - never COMPLETED")
    void wholeCarrierReturnLiquidates() {
        Order order = shipped();
        for (UUID shipment : List.of(S1, S2)) {
            order.recordShipmentDeliveryFailed(shipment, DeliveryFailureReason.REFUSED, null, day(1), STAFF);
            order.recordShipmentReturned(shipment, day(2), STAFF);
            order.closeReturnedShipment(shipment, day(3), day(3), STAFF);
        }
        order.refunded(true, STAFF, day(4), "DELIVERY_FAILED_SETTLED");
        found(order);
        UUID id = order.id().value();
        SalesContract contract = signedContract(7);
        when(contracts.findLiveByOrderId(id)).thenReturn(Optional.of(contract));
        when(contract.status()).thenReturn(SalesContractStatus.SIGNED, SalesContractStatus.LIQUIDATED);

        assertThat(at(day(40)).close(id)).isTrue();

        verify(contract).liquidate(any());
        verify(contract, never()).complete(any());
        verify(events).publish(org.mockito.ArgumentMatchers.argThat(event -> event instanceof SalesContractStatusChanged
                changed && "LIQUIDATED".equals(changed.status())));
    }

    @Test
    @DisplayName("Q-6: the contract of an order cancelled after signing is LIQUIDATED once nothing is owed either way")
    void cancelledAfterSigningLiquidates() {
        Order order = OrderFixtures.confirmed();
        order.cancel(com.stockflow.order.api.CancellationReasonCode.CUSTOMER_REQUEST, "khách huỷ", null, STAFF, NOW);
        found(order);
        UUID id = order.id().value();
        SalesContract contract = signedContract(7);
        when(contracts.findLiveByOrderId(id)).thenReturn(Optional.of(contract));
        when(payments.refundProgress(id)).thenReturn(new RefundProgress(new java.math.BigDecimal("300000"),
                java.math.BigDecimal.ZERO));

        assertThat(at(day(1)).liquidateCancelled(id)).as("its refund still to make").isFalse();
        verify(contract, never()).liquidate(any());

        when(payments.refundProgress(id)).thenReturn(new RefundProgress(new java.math.BigDecimal("300000"),
                new java.math.BigDecimal("300000")));
        assertThat(at(day(2)).liquidateCancelled(id)).isTrue();
        verify(contract).liquidate(day(2));
    }

    @Test
    @DisplayName("Q-17: PARTIALLY_FULFILLED → PARTIALLY_REFUNDED waits for a held order's release")
    void holdKeepsThePartRefundBack() {
        Order order = shipped();
        order.recordShipmentDelivered(S1, day(1), STAFF);
        order.recordShipmentDeliveryFailed(S2, DeliveryFailureReason.REFUSED, null, day(1), STAFF);
        order.recordShipmentReturned(S2, day(2), STAFF);
        order.closeReturnedShipment(S2, day(3), day(3), STAFF);
        found(order);
        UUID id = order.id().value();
        DeliveryFailureCases cases = mock(DeliveryFailureCases.class);
        when(cases.ofOrder(id)).thenReturn(List.of());
        when(payments.installmentStanding(id)).thenAnswer(call -> InstallmentStanding.none(id));
        DeliveryFailureService failures = new DeliveryFailureService(repository, cases,
                mock(OrderServiceImpl.class), mock(ShippingWarehouseService.class), payments, events,
                mock(org.springframework.context.ApplicationEventPublisher.class), candidates,
                Clock.fixed(day(4), ZoneOffset.UTC));
        when(candidates.completionHeld(id)).thenReturn(true);
        when(payments.refundProgress(id)).thenReturn(new RefundProgress(new java.math.BigDecimal("50000"),
                new java.math.BigDecimal("50000")));

        assertThat(failures.followRefunds(id, STAFF)).isFalse();
        assertThat(order.status()).isEqualTo(OrderStatus.PARTIALLY_FULFILLED);

        when(candidates.completionHeld(id)).thenReturn(false);
        assertThat(failures.followRefunds(id, STAFF)).isTrue();
        assertThat(order.status()).isEqualTo(OrderStatus.PARTIALLY_REFUNDED);
    }

    private DeliveryFailureService failures(Instant now) {
        DeliveryFailureCases cases = mock(DeliveryFailureCases.class);
        when(cases.ofOrder(any())).thenReturn(List.of());
        return new DeliveryFailureService(repository, cases, mock(OrderServiceImpl.class),
                mock(ShippingWarehouseService.class), payments, events,
                mock(org.springframework.context.ApplicationEventPublisher.class), candidates,
                Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("audit r2 M-6 (BE-45): S2 lost-closed or brought back and nothing made refundable - the order is never "
            + "\"Hoàn một phần\" at 0 đồng: it stays PARTIALLY_FULFILLED, and closes from there after the delay, its "
            + "contract COMPLETED (S1 was delivered)")
    void partEndedByTheCarrierWithNothingRefundableClosesWithoutARefund() {
        Order order = shipped();
        order.recordShipmentDelivered(S1, day(1), STAFF);
        order.recordShipmentDeliveryFailed(S2, DeliveryFailureReason.REFUSED, null, day(1), STAFF);
        order.recordShipmentReturned(S2, day(2), STAFF);
        order.closeReturnedShipment(S2, day(3), day(3), STAFF);
        found(order);
        UUID id = order.id().value();
        SalesContract contract = signedContract(7);
        when(contracts.findLiveByOrderId(id)).thenReturn(Optional.of(contract));

        assertThat(failures(day(3)).followRefunds(id, STAFF)).as("nothing refunded: not partly refunded").isFalse();
        assertThat(order.status()).isEqualTo(OrderStatus.PARTIALLY_FULFILLED);

        assertThat(at(day(20)).close(id)).as("inside the closing delay from its deliveries' end").isFalse();
        when(payments.refundProgress(id)).thenReturn(new RefundProgress(new java.math.BigDecimal("1"),
                new java.math.BigDecimal("1")));
        assertThat(at(day(40)).close(id)).as("money was made refundable: the refund branch's").isFalse();
        when(payments.refundProgress(id)).thenReturn(RefundProgress.NONE);
        when(candidates.completionHeld(id)).thenReturn(true);
        assertThat(at(day(40)).close(id)).as("held (Q-17)").isFalse();
        when(candidates.completionHeld(id)).thenReturn(false);

        assertThat(at(day(40)).close(id)).isTrue();
        assertThat(order.status()).isEqualTo(OrderStatus.CLOSED);
        assertThat(order.pullTransitions()).extracting(t -> t.from() + ">" + t.to())
                .last().isEqualTo("PARTIALLY_FULFILLED>CLOSED");
        verify(contract).complete(any());
        verify(contract, never()).liquidate(any());
    }

    @Test
    @DisplayName("audit r2 M-6 (BE-45): every shipment brought back, nothing paid - RETURNED, never PARTIALLY_REFUNDED; "
            + "closed after the delay with its contract LIQUIDATED (Q-6); never while the plate fee is owed")
    void wholeReturnWithNothingRefundableClosesWithoutARefund() {
        Order order = shipped();
        for (UUID shipment : List.of(S1, S2)) {
            order.recordShipmentDeliveryFailed(shipment, DeliveryFailureReason.REFUSED, null, day(1), STAFF);
            order.recordShipmentReturned(shipment, day(2), STAFF);
            order.closeReturnedShipment(shipment, day(3), day(3), STAFF);
        }
        found(order);
        UUID id = order.id().value();
        SalesContract contract = signedContract(7);
        when(contracts.findLiveByOrderId(id)).thenReturn(Optional.of(contract));
        when(contract.status()).thenReturn(SalesContractStatus.SIGNED, SalesContractStatus.LIQUIDATED);

        assertThat(failures(day(3)).followRefunds(id, STAFF)).isFalse();
        assertThat(order.status()).isEqualTo(OrderStatus.RETURNED);

        when(payments.installmentStanding(id)).thenReturn(new InstallmentStanding(id, true, true,
                java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, new java.math.BigDecimal("2700000"),
                java.math.BigDecimal.ZERO, null));
        assertThat(at(day(40)).close(id)).as("the plate fee is still owed").isFalse();
        when(payments.installmentStanding(id)).thenAnswer(call -> InstallmentStanding.none(id));

        assertThat(at(day(40)).close(id)).isTrue();
        assertThat(order.status()).isEqualTo(OrderStatus.CLOSED);
        verify(contract).liquidate(any());
        verify(contract, never()).complete(any());
    }

    @Test
    @DisplayName("audit r2 M-6: an order RETURNED by a customer's return is never closed without a refund - its claims "
            + "decide")
    void aCustomersReturnIsNotClosedWithoutARefund() {
        Order order = OrderFixtures.delivered(NOW);
        order.requestReturn(STAFF, day(1), "claim");
        order.goodsReturned(STAFF, day(2), "back");
        found(order);

        assertThat(order.endedByCarrier()).isFalse();
        assertThat(at(day(60)).close(order.id().value())).isFalse();
        assertThat(order.status()).isEqualTo(OrderStatus.RETURNED);
    }

    @Test
    @DisplayName("Q-7: a carrier's return not received yet is not settled - no refund may move the order")
    void noRefundBeforeTheReceipt() {
        Order order = shipped();
        for (UUID shipment : List.of(S1, S2)) {
            order.recordShipmentDeliveryFailed(shipment, DeliveryFailureReason.REFUSED, null, day(1), STAFF);
            order.recordShipmentReturned(shipment, day(2), STAFF);
        }
        found(order);
        UUID id = order.id().value();
        DeliveryFailureCases cases = mock(DeliveryFailureCases.class);
        when(cases.ofOrder(id)).thenReturn(List.of());
        DeliveryFailureService failures = new DeliveryFailureService(repository, cases,
                mock(OrderServiceImpl.class), mock(ShippingWarehouseService.class), payments, events,
                mock(org.springframework.context.ApplicationEventPublisher.class), candidates,
                Clock.fixed(day(4), ZoneOffset.UTC));

        assertThat(failures.followRefunds(id, STAFF)).isFalse();
        assertThat(order.status()).isEqualTo(OrderStatus.RETURNED);
    }

    @Test
    @DisplayName("a hold needs a delivery to verify; a REFUNDED / PARTIALLY_REFUNDED order may be held, a COMPLETED "
            + "one not")
    void holdApplicability() {
        com.stockflow.order.internal.repository.OrderCompletionHoldJpaRepository holds =
                mock(com.stockflow.order.internal.repository.OrderCompletionHoldJpaRepository.class);
        when(holds.findFirstByOrderIdAndReleasedAtIsNull(any())).thenReturn(Optional.empty());
        when(holds.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        OrderCompletionHolds service = new OrderCompletionHolds(repository, holds, mock(DeliveryFailureService.class),
                mock(ReturnRequestService.class), Clock.fixed(day(5), ZoneOffset.UTC));

        Order shipped = shipped();
        found(shipped);
        assertThat(shipped.completionHoldable()).isFalse();
        assertThat(((BusinessException) org.assertj.core.api.Assertions.catchThrowable(() -> service.hold(
                shipped.id().value(), CompletionHoldReason.DELIVERY_DISPUTED, null, STAFF)))
                .errorCode()).as("nothing delivered").isEqualTo(ErrorCode.ORDER_COMPLETION_HOLD_NOT_APPLICABLE);

        Order refunded = partlyRefunded();
        found(refunded);
        assertThat(refunded.completionHoldable()).as("what the settlement block offers").isTrue();
        assertThat(service.hold(refunded.id().value(), CompletionHoldReason.DELIVERY_DISPUTED,
                null, STAFF)).isNotNull();

        Order completed = OrderFixtures.delivered(NOW);
        assertThat(completed.completionHoldable()).as("DELIVERED").isTrue();
        completed.complete(day(30), Duration.ofDays(7), false, false, false);
        assertThat(completed.completionHoldable()).isFalse();
        found(completed);
        assertThat(((BusinessException) org.assertj.core.api.Assertions.catchThrowable(() -> service.hold(
                completed.id().value(), CompletionHoldReason.DELIVERY_DISPUTED, null,
                STAFF))).errorCode()).isEqualTo(ErrorCode.ORDER_COMPLETION_HOLD_NOT_APPLICABLE);

        // A late claim on it is open: still not held - the claim's end takes it back to COMPLETED.
        completed.requestReturn(STAFF, day(40), "late claim");
        assertThat(completed.completionHoldable()).isFalse();
        assertThat(((BusinessException) org.assertj.core.api.Assertions.catchThrowable(() -> service.hold(
                completed.id().value(), CompletionHoldReason.DELIVERY_DISPUTED, null,
                STAFF))).errorCode()).isEqualTo(ErrorCode.ORDER_COMPLETION_HOLD_NOT_APPLICABLE);
    }

    @Test
    @DisplayName("audit r2 L-1: releasing a hold follows the order on - both refund paths look again, since no refund "
            + "record will come to move an order the hold kept back")
    void releaseFollowsTheRefundBranch() {
        com.stockflow.order.internal.repository.OrderCompletionHoldJpaRepository holds =
                mock(com.stockflow.order.internal.repository.OrderCompletionHoldJpaRepository.class);
        Order order = partlyRefunded();
        found(order);
        UUID id = order.id().value();
        var open = new com.stockflow.order.internal.entity.OrderCompletionHoldJpaEntity(UUID.randomUUID(), id,
                CompletionHoldReason.DELIVERY_DISPUTED.name(), null, day(4), STAFF);
        when(holds.findFirstByOrderIdAndReleasedAtIsNull(id)).thenReturn(Optional.of(open));
        when(holds.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        DeliveryFailureService failures = mock(DeliveryFailureService.class);
        ReturnRequestService returns = mock(ReturnRequestService.class);
        OrderCompletionHolds service = new OrderCompletionHolds(repository, holds, failures, returns,
                Clock.fixed(day(5), ZoneOffset.UTC));

        service.release(id, "The carrier confirmed the delivery", STAFF);

        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(holds, returns, failures);
        inOrder.verify(holds).saveAndFlush(open);
        inOrder.verify(returns).followRefunds(id, STAFF);
        inOrder.verify(failures).followRefunds(id, STAFF);
    }
}
