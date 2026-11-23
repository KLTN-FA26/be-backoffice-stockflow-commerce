package com.stockflow.order.internal.service;

import com.stockflow.order.api.DeliveryInvoices;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.SalesContract;
import com.stockflow.order.internal.domain.SalesContractRepository;
import com.stockflow.order.internal.domain.SettlementCandidates;
import com.stockflow.order.internal.service.SettlementReadiness.Blocker;
import com.stockflow.order.internal.service.SettlementReadiness.Branch;
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
import java.util.Set;
import java.util.UUID;

import static com.stockflow.order.internal.service.OrderFixtures.NOW;
import static com.stockflow.order.internal.service.OrderFixtures.STAFF;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * WF-05 BE-34 (F-1): what an order waits for, read from the source the nightly settlement decides by. Each blocker
 * appears exactly when the step it names would refuse, and a return's unissued adjustment is information only (Q-16).
 */
class SettlementReadinessTest {

    private final SettlementCandidates candidates = mock(SettlementCandidates.class);
    private final PaymentService payments = mock(PaymentService.class);
    private final SalesContractRepository contracts = mock(SalesContractRepository.class);
    private final DeliveryInvoices invoices = mock(DeliveryInvoices.class);

    {
        when(payments.installmentStanding(any())).thenAnswer(call -> InstallmentStanding.none(call.getArgument(0)));
        when(payments.refundProgress(any())).thenReturn(RefundProgress.NONE);
        when(invoices.invoiced(any())).thenReturn(Set.of());
    }

    private SettlementReadiness at(Instant now) {
        return new SettlementReadiness(candidates, payments, contracts, invoices, Clock.fixed(now, ZoneOffset.UTC), 7,
                30);
    }

    @Test
    @DisplayName("nothing delivered: no readiness to show")
    void nothingDelivered() {
        assertThat(at(NOW).view(OrderFixtures.released())).isNull();
    }

    @Test
    @DisplayName("DELIVERED inside the contract's window: COMPLETION, RETURN_WINDOW_OPEN, completesAt at the window's "
            + "end; past it with nothing else, no blocker")
    void deliveredWaitsForTheWindow() {
        Order order = OrderFixtures.delivered(NOW);
        SalesContract contract = mock(SalesContract.class);
        when(contract.returnWindowDays()).thenReturn(14);
        when(contracts.findLiveByOrderId(order.id().value())).thenReturn(Optional.of(contract));

        SettlementReadiness.View inside = at(NOW.plus(Duration.ofDays(3))).view(order);
        assertThat(inside.branch()).isEqualTo(Branch.COMPLETION);
        assertThat(inside.returnWindowDays()).isEqualTo(14);
        assertThat(inside.blockers()).containsExactly(Blocker.RETURN_WINDOW_OPEN);
        assertThat(inside.completesAt()).isEqualTo(com.stockflow.order.internal.domain.ReturnPolicy.endOfDays(NOW,
                Duration.ofDays(14)));

        assertThat(at(NOW.plus(Duration.ofDays(20))).view(order).blockers()).isEmpty();
    }

    @Test
    @DisplayName("every blocker shows when its fact holds; an unissued return adjustment is information only")
    void blockers() {
        Order order = OrderFixtures.delivered(NOW);
        UUID id = order.id().value();
        when(payments.installmentStanding(id)).thenReturn(new InstallmentStanding(id, true, true, BigDecimal.ZERO,
                BigDecimal.ZERO, new BigDecimal("1000"), new BigDecimal("400"),
                InstallmentStanding.ContractPaymentStatus.PARTIALLY_PAID));
        when(candidates.hasOpenReturn(id)).thenReturn(true);
        when(candidates.completionHeld(id)).thenReturn(true);
        when(invoices.returnAdjustmentUnissued(id)).thenReturn(true);

        SettlementReadiness.View view = at(NOW.plus(Duration.ofDays(20))).view(order);

        assertThat(view.blockers()).containsExactlyInAnyOrder(Blocker.OWING, Blocker.OPEN_RETURN,
                Blocker.COMPLETION_HELD);
        assertThat(view.returnAdjustmentUnissued()).isTrue();
        assertThat(view.owing()).isTrue();
    }

    @Test
    @DisplayName("a refunded order: REFUND_CLOSE, REFUND_OUTSTANDING while a refund is owed, closesAt the later of "
            + "the closing delay and the window")
    void refundBranch() {
        Order order = OrderFixtures.delivered(NOW);
        order.requestReturn(STAFF, NOW.plus(Duration.ofDays(1)), null);
        order.goodsReturned(STAFF, NOW.plus(Duration.ofDays(2)), null);
        order.refunded(false, STAFF, NOW.plus(Duration.ofDays(3)), "REFUNDED");
        UUID id = order.id().value();
        when(payments.refundProgress(id)).thenReturn(new RefundProgress(new BigDecimal("500"), BigDecimal.ZERO));

        SettlementReadiness.View view = at(NOW.plus(Duration.ofDays(5))).view(order);

        assertThat(view.branch()).isEqualTo(Branch.REFUND_CLOSE);
        assertThat(view.blockers()).contains(Blocker.REFUND_OUTSTANDING);
        assertThat(view.closesAt()).isEqualTo(NOW.plus(Duration.ofDays(33)));
    }
}
