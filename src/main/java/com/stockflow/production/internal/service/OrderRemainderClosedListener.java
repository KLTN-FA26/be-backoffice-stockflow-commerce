package com.stockflow.production.internal.service;

import com.stockflow.contracts.OrderRemainderClosed;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * PACK-EARLY: the rest of a partly fulfilled order was closed - for a mixed order whose stock shipped ahead of its
 * prints, the print lines too. Their production orders stop, as on a cancellation ({@link OrderCancellationListener}):
 * after commit, in its own transaction, retried by Spring Modulith's registry when it fails. Lines with no production
 * order (stock lines) are not production's.
 */
@Component("productionOrderRemainderClosedListener")
class OrderRemainderClosedListener {

    private final PrintRunService runs;

    OrderRemainderClosedListener(PrintRunService runs) {
        this.runs = runs;
    }

    @ApplicationModuleListener
    public void on(OrderRemainderClosed event) {
        Set<UUID> lines = event.lines().stream().map(OrderRemainderClosed.Line::orderLineId)
                .collect(Collectors.toSet());
        runs.onLinesClosed(event.orderId(), event.orderNumber(), lines, event.closedBy());
    }
}
