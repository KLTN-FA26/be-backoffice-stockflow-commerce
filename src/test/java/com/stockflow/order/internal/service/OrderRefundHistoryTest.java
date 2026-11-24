package com.stockflow.order.internal.service;

import com.stockflow.order.api.DeliveryInvoices;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderRepository;
import com.stockflow.order.internal.domain.SalesContractRepository;
import com.stockflow.order.internal.domain.SettlementCandidates;
import com.stockflow.order.internal.repository.OrderHistoryNotes;
import com.stockflow.payment.api.InstallmentStanding;
import com.stockflow.payment.api.PaymentService;
import com.stockflow.payment.api.RefundProgress;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
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
 * WF-05 BE-52 (M-11): a refund record voided while a claim of the order is open moves no status, and is still written
 * into the order's history ({@code REFUND_VOIDED}); a void that moves the order writes its status row only.
 */
class OrderRefundHistoryTest {

    private static final Instant LATER = NOW.plus(Duration.ofDays(10));

    private final OrderRepository repository = mock(OrderRepository.class);
    private final PaymentService payments = mock(PaymentService.class);
    private final OrderHistoryNotes notes = mock(OrderHistoryNotes.class);
    private final OrderSettlementService service;

    {
        SettlementCandidates candidates = mock(SettlementCandidates.class);
        SalesContractRepository contracts = mock(SalesContractRepository.class);
        Clock clock = Clock.fixed(LATER, ZoneOffset.UTC);
        when(payments.installmentStanding(any())).thenAnswer(call -> InstallmentStanding.none(call.getArgument(0)));
        when(payments.refundVoidReason(any())).thenReturn(Optional.of("tài khoản sai"));
        when(payments.refundProgress(any())).thenReturn(new RefundProgress(new BigDecimal("500000"),
                BigDecimal.ZERO));
        service = new OrderSettlementService(repository, candidates, mock(OrderEventPublisher.class), payments,
                contracts, mock(DeliveryFailureService.class),
                new SettlementReadiness(candidates, payments, contracts, mock(DeliveryInvoices.class), clock, 7, 30),
                notes, clock, 7, 30);
    }

    private Order found(Order order) {
        when(repository.findByIdForUpdate(order.id())).thenReturn(Optional.of(order));
        return order;
    }

    @Test
    @DisplayName("the goods of a second claim are back (RETURNED) when the refund is voided: the order stays, the void "
            + "is in its history")
    void voidWhileGoodsAreBack() {
        Order order = found(OrderFixtures.delivered(NOW));
        order.requestReturn(STAFF, NOW.plusSeconds(60), null);
        order.goodsReturned(STAFF, NOW.plusSeconds(120), null);
        UUID record = UUID.randomUUID();

        assertThat(service.refundVoided(order.id().value(), record, STAFF)).isFalse();

        assertThat(order.status()).isEqualTo(OrderStatus.RETURNED);
        verify(notes).refundVoided(order.id().value(), record, "tài khoản sai", STAFF, LATER);
    }

    @Test
    @DisplayName("a further claim open on a refunded order: no status move, the void in its history, and the claim "
            + "ends in waiting for the refund")
    void voidWhileFurtherClaimOpen() {
        Order order = found(OrderFixtures.delivered(NOW));
        order.requestReturn(STAFF, NOW.plusSeconds(60), null);
        order.goodsReturned(STAFF, NOW.plusSeconds(120), null);
        order.refunded(true, STAFF, NOW.plusSeconds(180), "REFUNDED");
        order.requestReturn(STAFF, NOW.plusSeconds(240), "further claim");
        UUID record = UUID.randomUUID();

        assertThat(service.refundVoided(order.id().value(), record, STAFF)).isFalse();

        assertThat(order.status()).isEqualTo(OrderStatus.RETURN_REQUESTED);
        assertThat(order.statusBeforeReturn()).isEqualTo(OrderStatus.RETURNED);
        verify(notes).refundVoided(order.id().value(), record, "tài khoản sai", STAFF, LATER);
        verify(repository).save(order);
    }

    @Test
    @DisplayName("a refunded order with no claim open goes back to RETURNED; its status row records the void")
    void voidMovesTheOrder() {
        Order order = found(OrderFixtures.delivered(NOW));
        order.requestReturn(STAFF, NOW.plusSeconds(60), null);
        order.goodsReturned(STAFF, NOW.plusSeconds(120), null);
        order.refunded(true, STAFF, NOW.plusSeconds(180), "REFUNDED");

        assertThat(service.refundVoided(order.id().value(), UUID.randomUUID(), STAFF)).isTrue();

        assertThat(order.status()).isEqualTo(OrderStatus.RETURNED);
        verify(notes, never()).refundVoided(any(), any(), any(), any(), any());
    }
}
