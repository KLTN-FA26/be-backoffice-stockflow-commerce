package com.stockflow.inventory.api;

import java.util.Objects;
import java.util.UUID;

/**
 * Intent: a picker found fewer units than an allocation promised (X-SHORT); release that allocation and allocate
 * another carton of the same count (ADR-0011) at the same warehouse, never the carton that came up short - its count
 * is in doubt until somebody recounts it. The other cartons of its bin were each scanned by their own LPN and stay
 * pickable (kltn-docs 07 BR-09).
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
