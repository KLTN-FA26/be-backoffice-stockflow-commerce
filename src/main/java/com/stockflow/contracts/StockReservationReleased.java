package com.stockflow.contracts;

import java.util.UUID;

/**
 * Published by the <b>inventory</b> module. Consumers receive it in-process through
 * {@code @ApplicationModuleListener}; delivery is made durable by Spring Modulith's
 * event publication registry, so a listener that crashes mid-handling gets the event again.
 * See {@code package-info.java} for the rules on changing this contract.
 */
public record StockReservationReleased(
        UUID orderId,
        String sku,
        int quantity,
        ReleaseReason reason
) {
}
