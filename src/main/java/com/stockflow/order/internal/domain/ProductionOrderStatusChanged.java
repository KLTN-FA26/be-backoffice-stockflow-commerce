package com.stockflow.order.internal.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * A production order changed status - one {@link ProductionOrder.Change}, saved (4A-6). An application event inside
 * the order module, not a contract: the repository publishes one for every change it writes to the status history,
 * whichever path made it (approval, sample rounds, progress, receiving), and the sales order reacts in the same
 * transaction ({@code ProductionOrderProgressListener}): waiting for stock once one is sent, printing started once one
 * is in production.
 *
 * @param from null for the draft it was drawn up as
 * @param by   who made the change
 */
public record ProductionOrderStatusChanged(UUID productionOrderId, String code, UUID salesOrderId,
                                           UUID deliveryBatchId, ProductionOrderStatus from, ProductionOrderStatus to,
                                           UUID by, Instant at) {
}
