package com.stockflow.order.internal.service;

import com.stockflow.contracts.ReleaseReason;
import com.stockflow.contracts.StockReservationReleased;
import com.stockflow.order.internal.domain.ContractOrderSaved;
import com.stockflow.order.internal.domain.ProductionOrderSaved;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Review of 4A-7: a recompute that fails is thrown out of the listener, so Spring Modulith leaves the event's
 * publication incomplete and resubmits it, instead of completing it and losing the trigger until the nightly sweep.
 */
class DelayAlertListenerTest {

    @Test
    @DisplayName("a failing recompute propagates: the publication stays incomplete")
    void failureIsNotSwallowed() {
        DelayAlertService alerts = mock(DelayAlertService.class);
        UUID orderId = UUID.randomUUID();
        when(alerts.recompute(orderId)).thenThrow(new CannotAcquireLockException("lock timeout"));
        DelayAlertListener listener = new DelayAlertListener(alerts);

        assertThatThrownBy(() -> listener.on(new ProductionOrderSaved(UUID.randomUUID(), orderId)))
                .isInstanceOf(CannotAcquireLockException.class);
        assertThatThrownBy(() -> listener.on(new ContractOrderSaved(orderId)))
                .isInstanceOf(CannotAcquireLockException.class);
    }

    @Test
    @DisplayName("each trigger recomputes the order it names")
    void recomputesTheNamedOrder() {
        DelayAlertService alerts = mock(DelayAlertService.class);
        DelayAlertListener listener = new DelayAlertListener(alerts);
        UUID drawnUp = UUID.randomUUID();
        UUID confirmed = UUID.randomUUID();
        UUID freed = UUID.randomUUID();

        listener.on(new ProductionOrderSaved(UUID.randomUUID(), drawnUp));
        listener.on(new ContractOrderSaved(confirmed));
        listener.on(new StockReservationReleased(freed, "SKU-1", 50, ReleaseReason.ORDER_CANCELLED));

        verify(alerts).recompute(drawnUp);
        verify(alerts).recompute(confirmed);
        verify(alerts).recompute(freed);
    }

    @Test
    @DisplayName("a hold released for no order recomputes nothing")
    void holdOfNoOrder() {
        DelayAlertService alerts = mock(DelayAlertService.class);

        new DelayAlertListener(alerts).on(new StockReservationReleased(null, "SKU-1", 50,
                ReleaseReason.RESERVATION_EXPIRED));

        verifyNoInteractions(alerts);
    }
}
