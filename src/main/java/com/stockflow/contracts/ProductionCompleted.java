package com.stockflow.contracts;

import java.util.UUID;

/**
 * Published by the <b>production</b> module when an {@code ORDER} production order finishes and
 * its good output has been received into stock. The <b>order</b> module moves the order to
 * {@code READY_TO_FULFILL} once every released line has completed.
 *
 * <p>Per line, not per order: production orders of one customer order finish on different days,
 * and a subcontracted part (plan §8) finishes on its own production order. The order module
 * counts lines, so an event delivered twice must not count twice — it is keyed by
 * {@code productionOrderId}.</p>
 *
 * <p>{@code goodQuantity} is what passed QC, not what was printed. It may be lower than ordered;
 * the order module decides whether that is a short shipment or a reprint, production does not.</p>
 *
 * <p>Consumers receive it in-process through {@code @ApplicationModuleListener}; delivery is made
 * durable by Spring Modulith's event publication registry. See {@code package-info.java} for the
 * rules on changing this contract.</p>
 */
public record ProductionCompleted(
        UUID orderId,
        UUID orderLineId,
        UUID productionOrderId,
        int goodQuantity
) {
}
