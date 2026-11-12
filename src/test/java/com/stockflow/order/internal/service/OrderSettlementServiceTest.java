package com.stockflow.order.internal.service;

import com.stockflow.order.api.DeliveryInvoices;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.ShipmentHandover;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderRepository;
import com.stockflow.order.internal.domain.SalesContract;
import com.stockflow.order.internal.domain.SalesContractRepository;
import com.stockflow.order.internal.domain.SettlementCandidates;
import com.stockflow.payment.api.InstallmentStanding;
import com.stockflow.payment.api.PaymentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
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
 * kltn-docs 17 §4.3 step 10 and BR-05: a delivered order completes once the return window has passed, a completed or
 * refunded one closes after the closing delay; the aggregate decides, the service looks the candidates up, saves and
 * announces.
 */
class OrderSettlementServiceTest {

    private static final Instant LATER = NOW.plus(Duration.ofDays(8));

    private final OrderRepository repository = mock(OrderRepository.class);
    private final SettlementCandidates candidates = mock(SettlementCandidates.class);
    private final OrderEventPublisher events = mock(OrderEventPublisher.class);
    private final PaymentService payments = mock(PaymentService.class);
    private final SalesContractRepository contracts = mock(SalesContractRepository.class);
    private final DeliveryInvoices invoices = mock(DeliveryInvoices.class);

    {
        // Without installments an order owes nothing in the system (4B-10).
        when(payments.installmentStanding(any())).thenAnswer(call -> InstallmentStanding.none(call.getArgument(0)));
        // Every delivery invoiced unless a test says otherwise (WF-05 condition 5).
        // Null-tolerant: a test re-stubbing with when(invoices.invoiced(any())) calls this answer with null.
        when(invoices.invoiced(any())).thenAnswer(call -> call.getArgument(0) == null ? Set.of()
                : Set.copyOf(call.<java.util.Collection<UUID>>getArgument(0)));
    }

    private OrderSettlementService at(Instant now) {
        return new OrderSettlementService(repository, candidates, events, payments, contracts, invoices,
                Clock.fixed(now, ZoneOffset.UTC), 7, 30);
    }

    @Test
    @DisplayName("completion candidates are the orders delivered at or before now minus the return window, after the ones skipped")
    void completionCandidatesUseTheReturnWindow() {
        List<UUID> found = List.of(UUID.randomUUID());
        when(candidates.deliveredBy(NOW.minus(Duration.ofDays(7)), 5, 50)).thenReturn(found);

        assertThat(at(NOW).completionCandidates(5, 50)).isEqualTo(found);
    }

    @Test
    @DisplayName("closing candidates are the orders settled at or before now minus the closing delay (default 30 days)")
    void closingCandidatesUseTheClosingDelay() {
        List<UUID> found = List.of(UUID.randomUUID());
        when(candidates.settledBy(NOW.minus(Duration.ofDays(30)), 0, 10)).thenReturn(found);

        assertThat(at(NOW).closingCandidates(0, 10)).isEqualTo(found);
    }

    @Test
    @DisplayName("a delivered and paid order past the return window is completed, saved and announced")
    void completesAfterTheReturnWindow() {
        Order order = OrderFixtures.delivered(NOW);
        when(repository.findByIdForUpdate(order.id())).thenReturn(Optional.of(order));

        assertThat(at(LATER).complete(order.id().value())).isTrue();

        assertThat(order.status()).isEqualTo(OrderStatus.COMPLETED);
        verify(repository).save(order);
        verify(events).publishEventsOf(order);
    }

    @Test
    @DisplayName("inside the return window nothing changes and nothing is saved (BR-ORD-005)")
    void staysDeliveredInsideTheWindow() {
        Order order = OrderFixtures.delivered(NOW);
        when(repository.findByIdForUpdate(order.id())).thenReturn(Optional.of(order));

        assertThat(at(NOW.plus(Duration.ofDays(6))).complete(order.id().value())).isFalse();

        assertThat(order.status()).isEqualTo(OrderStatus.DELIVERED);
        verify(repository, never()).save(any());
        verify(events, never()).publishEventsOf(any());
    }

    @Test
    @DisplayName("an open return request keeps the order from completing")
    void anOpenReturnBlocksCompletion() {
        Order order = OrderFixtures.delivered(NOW);
        when(repository.findByIdForUpdate(order.id())).thenReturn(Optional.of(order));
        when(candidates.hasOpenReturn(order.id().value())).thenReturn(true);

        assertThat(at(LATER).complete(order.id().value())).isFalse();

        assertThat(order.status()).isEqualTo(OrderStatus.DELIVERED);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("an order that no longer exists is skipped, not an error")
    void aMissingOrderIsSkipped() {
        UUID gone = UUID.randomUUID();
        when(repository.findByIdForUpdate(new com.stockflow.order.internal.domain.OrderId(gone)))
                .thenReturn(Optional.empty());

        assertThat(at(LATER).complete(gone)).isFalse();
        assertThat(at(LATER).close(gone)).isFalse();
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("a completed order is closed after the closing delay and not before (BR-05, G3-Q1)")
    void closesAfterTheClosingDelay() {
        Order order = OrderFixtures.delivered(NOW);
        order.complete(LATER, Duration.ofDays(7), false, false, false);
        order.pullDomainEvents();
        when(repository.findByIdForUpdate(order.id())).thenReturn(Optional.of(order));

        assertThat(at(LATER.plus(Duration.ofDays(29))).close(order.id().value())).isFalse();
        verify(repository, never()).save(any());

        assertThat(at(LATER.plus(Duration.ofDays(30))).close(order.id().value())).isTrue();
        assertThat(order.status()).isEqualTo(OrderStatus.CLOSED);
        verify(repository).save(order);
        verify(events).publishEventsOf(order);
    }

    @Test
    @DisplayName("an order that is not settled is never closed")
    void aDeliveredOrderIsNotClosed() {
        Order order = OrderFixtures.delivered(NOW);
        when(repository.findByIdForUpdate(order.id())).thenReturn(Optional.of(order));

        assertThat(at(NOW.plus(Duration.ofDays(400))).close(order.id().value())).isFalse();
        verify(repository, never()).save(any());
        verify(events, never()).publishEventsOf(any());
    }

    private static InstallmentStanding standing(UUID order, long total, long paid) {
        return new InstallmentStanding(order, true, true, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.valueOf(total),
                BigDecimal.valueOf(paid), null);
    }

    @Test
    @DisplayName("4B-10 (17 §4.6 step 21): an order whose installments still owe is not completed, nor closed; paid "
            + "in full it is")
    void anOrderStillOwingIsNeitherCompletedNorClosed() {
        Order order = OrderFixtures.delivered(NOW);
        when(repository.findByIdForUpdate(order.id())).thenReturn(Optional.of(order));
        when(payments.installmentStanding(order.id().value())).thenReturn(standing(order.id().value(), 1_000, 400));

        assertThat(at(LATER).complete(order.id().value())).isFalse();
        assertThat(order.status()).isEqualTo(OrderStatus.DELIVERED);
        verify(repository, never()).save(any());

        when(payments.installmentStanding(order.id().value())).thenReturn(standing(order.id().value(), 1_000, 1_000));
        assertThat(at(LATER).complete(order.id().value())).isTrue();
        assertThat(order.status()).isEqualTo(OrderStatus.COMPLETED);

        when(payments.installmentStanding(order.id().value())).thenReturn(standing(order.id().value(), 1_000, 400));
        assertThat(at(LATER.plus(Duration.ofDays(31))).close(order.id().value())).isFalse();
        assertThat(order.status()).isEqualTo(OrderStatus.COMPLETED);
    }

    // ---- WF-05 plan 4, condition 5: every delivered shipment has its e-invoice issued ---------------------------------

    /** Released, picked, packed and handed over whole on one shipment, delivered at NOW. */
    private static Order deliveredOn(UUID shipment) {
        Order order = OrderFixtures.released();
        order.startPicking(STAFF, NOW);
        order.markPacked(NOW, STAFF);
        order.recordHandover(new ShipmentHandover(shipment, List.of(
                new ShipmentHandover.Line(order.lines().get(0).id(), 10),
                new ShipmentHandover.Line(order.lines().get(1).id(), 5)), NOW, STAFF));
        order.recordShipmentDelivered(shipment, NOW, STAFF);
        order.pullDomainEvents();
        order.pullTransitions();
        assertThat(order.status()).isEqualTo(OrderStatus.DELIVERED);
        return order;
    }

    @Test
    @DisplayName("condition 5: a delivered shipment without an issued e-invoice keeps the order DELIVERED and its "
            + "contract untouched; invoiced, the order completes and its contract with it, saved together")
    void aDeliveryNotInvoicedKeepsTheOrderDelivered() {
        UUID shipment = UUID.randomUUID();
        Order order = deliveredOn(shipment);
        SalesContract contract = mock(SalesContract.class);
        when(contract.complete(any())).thenReturn(true);
        when(repository.findByIdForUpdate(order.id())).thenReturn(Optional.of(order));
        when(contracts.findLiveByOrderId(order.id().value())).thenReturn(Optional.of(contract));
        when(invoices.invoiced(List.of(shipment))).thenReturn(Set.of());

        assertThat(at(LATER).complete(order.id().value())).isFalse();

        assertThat(order.status()).isEqualTo(OrderStatus.DELIVERED);
        verify(repository, never()).save(any());
        verify(contract, never()).complete(any());
        verify(contracts, never()).save(any());
        verify(events, never()).publishEventsOf(any());

        when(invoices.invoiced(List.of(shipment))).thenReturn(Set.of(shipment));
        assertThat(at(LATER).complete(order.id().value())).isTrue();

        assertThat(order.status()).isEqualTo(OrderStatus.COMPLETED);
        verify(repository).save(order);
        verify(contract).complete(LATER);
        verify(contracts).save(contract);
        verify(events).publishEventsOf(order);
    }

    @Test
    @DisplayName("condition 5: an invoice of some other shipment does not stand in for the delivered one")
    void anotherShipmentsInvoiceDoesNotCount() {
        UUID shipment = UUID.randomUUID();
        Order order = deliveredOn(shipment);
        when(repository.findByIdForUpdate(order.id())).thenReturn(Optional.of(order));
        when(invoices.invoiced(any())).thenReturn(Set.of(UUID.randomUUID()));

        assertThat(at(LATER).complete(order.id().value())).isFalse();
        assertThat(order.status()).isEqualTo(OrderStatus.DELIVERED);
    }

    @Test
    @DisplayName("condition 5: an order delivered without shipments has no delivery to invoice - invoicing is not asked")
    void anOrderWithoutShipmentsWaitsForNoInvoice() {
        Order order = OrderFixtures.delivered(NOW);
        when(repository.findByIdForUpdate(order.id())).thenReturn(Optional.of(order));

        assertThat(at(LATER).complete(order.id().value())).isTrue();

        verify(invoices, never()).invoiced(any());
    }

    @Test
    @DisplayName("condition 5 does not replace the others: every shipment invoiced, an order still owing stays DELIVERED")
    void invoicedButOwingStaysDelivered() {
        UUID shipment = UUID.randomUUID();
        Order order = deliveredOn(shipment);
        when(repository.findByIdForUpdate(order.id())).thenReturn(Optional.of(order));
        when(invoices.invoiced(any())).thenReturn(Set.of(shipment));
        when(payments.installmentStanding(order.id().value())).thenReturn(standing(order.id().value(), 1_000, 400));

        assertThat(at(LATER).complete(order.id().value())).isFalse();
        assertThat(order.status()).isEqualTo(OrderStatus.DELIVERED);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("condition 5, the shipping-fee rest: every delivery invoiced but the fee rest not issued keeps the "
            + "order DELIVERED; issued, the order completes")
    void anUnissuedShippingFeeRestKeepsTheOrderDelivered() {
        UUID shipment = UUID.randomUUID();
        Order order = deliveredOn(shipment);
        when(repository.findByIdForUpdate(order.id())).thenReturn(Optional.of(order));
        when(invoices.shippingFeeUnissued(order.id().value())).thenReturn(true);

        assertThat(at(LATER).complete(order.id().value())).isFalse();
        assertThat(order.status()).isEqualTo(OrderStatus.DELIVERED);
        verify(repository, never()).save(any());

        when(invoices.shippingFeeUnissued(order.id().value())).thenReturn(false);
        assertThat(at(LATER).complete(order.id().value())).isTrue();
        assertThat(order.status()).isEqualTo(OrderStatus.COMPLETED);
    }
}
