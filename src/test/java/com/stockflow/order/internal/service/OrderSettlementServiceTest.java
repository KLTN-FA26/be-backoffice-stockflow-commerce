package com.stockflow.order.internal.service;

import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderRepository;
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
import java.util.UUID;

import static com.stockflow.order.internal.service.OrderFixtures.NOW;
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

    {
        // Without installments an order owes nothing in the system (4B-10).
        when(payments.installmentStanding(any())).thenAnswer(call -> InstallmentStanding.none(call.getArgument(0)));
    }

    private OrderSettlementService at(Instant now) {
        return new OrderSettlementService(repository, candidates, events, payments, org.mockito.Mockito.mock(com.stockflow.order.internal.domain.SalesContractRepository.class), org.mockito.Mockito.mock(DeliveryFailureService.class), Clock.fixed(now, ZoneOffset.UTC), 7, 30);
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
        order.complete(LATER, Duration.ofDays(7), false, false);
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
}
