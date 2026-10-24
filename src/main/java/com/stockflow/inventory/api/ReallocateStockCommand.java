package com.stockflow.inventory.api;

import java.util.Objects;
import java.util.UUID;

/**
 * Intent: a picker found fewer units than an allocation promised (X-SHORT); release that allocation and allocate
 * the same quantity again from other pickable bins of the same warehouse, FEFO, never from the bin that came up
 * short — its count is in doubt until somebody recounts it.
 *
 * @param reservationId the allocation the short pick line was picking
 * @param requestId     idempotency key: the same request answers with the allocations it already made
 */
public record ReallocateStockCommand(UUID orderId, UUID reservationId, UUID requestId) {

    public ReallocateStockCommand {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(reservationId, "reservationId");
        Objects.requireNonNull(requestId, "requestId");
    }
}
