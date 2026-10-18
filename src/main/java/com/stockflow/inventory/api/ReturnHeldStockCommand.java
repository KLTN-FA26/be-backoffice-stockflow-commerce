package com.stockflow.inventory.api;

import java.util.UUID;

/**
 * Blanks issued to the print shop and not printed go back to storage (SCRUM-400 leftovers, SCRUM-401
 * cancellation): {@code quantity} units leave the live hold and arrive at {@code toLocation} as free, AVAILABLE
 * stock. One MOVE ledger
 * line, reference {@code PRODUCTION_ORDER}. Printed goods never take this way back (Q3): the caller returns
 * only what it counts as unprinted.
 */
public record ReturnHeldStockCommand(UUID reservationId, UUID orderId, UUID productionOrderId, int quantity,
                                     String toLocation, UUID actorId) {

    public ReturnHeldStockCommand {
        if (reservationId == null) throw new IllegalArgumentException("reservationId is required");
        if (productionOrderId == null) throw new IllegalArgumentException("productionOrderId is required");
        if (quantity < 1) throw new IllegalArgumentException("quantity must be at least 1");
        if (toLocation == null || toLocation.isBlank()) throw new IllegalArgumentException("toLocation is required");
    }
}
