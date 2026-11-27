package com.stockflow.order.internal.service;

import com.stockflow.order.internal.domain.SalesFollowUps;
import com.stockflow.payment.api.InstallmentStanding;
import com.stockflow.payment.api.PaymentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * WF-02 CONC-2: an order still PENDING_PAYMENT whose installments on signing are paid is handed to the listener's own
 * confirmation; the others are left alone, and one that fails does not stop the rest.
 */
class InstallmentReconciliationTest {

    private final SalesFollowUps followUps = mock(SalesFollowUps.class);
    private final PaymentService payments = mock(PaymentService.class);
    private final OrderServiceImpl orders = mock(OrderServiceImpl.class);
    private final InstallmentReconciliation reconciliation = new InstallmentReconciliation(followUps, payments, orders);

    @Test
    @DisplayName("only orders whose installments on signing are paid are confirmed; a failure is logged, not thrown")
    void confirmsThePaidOnes() {
        UUID paid = UUID.randomUUID();
        UUID failing = UUID.randomUUID();
        UUID unpaid = UUID.randomUUID();
        UUID noPlan = UUID.randomUUID();
        List<UUID> waiting = List.of(paid, failing, unpaid, noPlan);
        when(followUps.awaitingOnSigning(InstallmentReconciliation.LIMIT)).thenReturn(waiting);
        when(payments.installmentStandings(waiting)).thenReturn(Map.of(
                paid, standing(paid, true), failing, standing(failing, true), unpaid, standing(unpaid, false)));
        doThrow(new IllegalStateException("lock timeout")).when(orders).installmentsPaid(failing, null);

        assertThat(reconciliation.confirmPaid()).isEqualTo(1);

        verify(orders).installmentsPaid(paid, null);
        verify(orders).installmentsPaid(failing, null);
        verify(orders, never()).installmentsPaid(unpaid, null);
        verify(orders, never()).installmentsPaid(noPlan, null);
    }

    @Test
    @DisplayName("nothing waiting: payment is not asked")
    void nothingWaiting() {
        when(followUps.awaitingOnSigning(anyInt())).thenReturn(List.of());

        assertThat(reconciliation.confirmPaid()).isZero();

        verifyNoInteractions(payments, orders);
    }

    private static InstallmentStanding standing(UUID orderId, boolean onSigningPaid) {
        return new InstallmentStanding(orderId, true, onSigningPaid, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.TEN, onSigningPaid ? BigDecimal.TEN : BigDecimal.ZERO, null);
    }
}
