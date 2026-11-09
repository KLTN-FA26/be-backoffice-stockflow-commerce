package com.stockflow.order.internal.service;

import com.stockflow.contracts.OrderReleased;
import com.stockflow.order.internal.domain.ProductionOrderSaved;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
        assertThatThrownBy(() -> listener.on(new OrderReleased(orderId, "W1", Instant.now())))
                .isInstanceOf(CannotAcquireLockException.class);
    }

    @Test
    @DisplayName("each trigger recomputes the order it names")
    void recomputesTheNamedOrder() {
        DelayAlertService alerts = mock(DelayAlertService.class);
        UUID orderId = UUID.randomUUID();

        new DelayAlertListener(alerts).on(new ProductionOrderSaved(UUID.randomUUID(), orderId));

        verify(alerts).recompute(orderId);
    }
}
