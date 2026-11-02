package com.stockflow.reporting.internal.service;

import com.stockflow.contracts.OrderRemainderClosed;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** Which closures the sales rollups take off: only a short completion (owner decision SHORT-LIMITS, 2026-11-02). */
class SalesRollupListenerTest {

    private final SalesRollupService service = mock(SalesRollupService.class);
    private final SalesRollupListener listener = new SalesRollupListener(service);

    private static OrderRemainderClosed closed(String reason) {
        return new OrderRemainderClosed(UUID.randomUUID(), "SO-1", UUID.randomUUID(), reason,
                List.of(new OrderRemainderClosed.Line(UUID.randomUUID(), "CUP", 3)), new BigDecimal("30"),
                new BigDecimal("70"), BigDecimal.ZERO, BigDecimal.ZERO, "VND", UUID.randomUUID(), Instant.now(),
                UUID.randomUUID());
    }

    @Test
    @DisplayName("a production shortfall is applied to the rollups")
    void shortfallIsApplied() {
        OrderRemainderClosed event = closed(OrderRemainderClosed.PRODUCTION_SHORTFALL);

        listener.on(event);

        verify(service).productionShortfallClosed(event);
    }

    @Test
    @DisplayName("closing the rest of a split order is left as it was: not applied")
    void otherClosuresAreNot() {
        listener.on(closed("CUSTOMER_REQUEST"));

        verifyNoInteractions(service);
    }
}
