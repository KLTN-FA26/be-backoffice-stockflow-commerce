package com.stockflow.inventory.api;

import java.util.UUID;

/**
 * Issue one live hold, with its goods, to a location of the same warehouse — blanks to the
 * {@code PRODUCTION} area (SCRUM-397), printed goods on to {@code PACKING} (SCRUM-400).
 *
 * <p>The hold must belong to {@code orderId} (the order's checkout hold) or to {@code productionOrderId} (a
 * sample's or a reprint's hold). The whole hold moves: its quantity is fixed
 * when it is made, and splitting it would hand the order a reservation id it never stored.</p>
 *
 * @param productionOrderId the document behind the move, written on the ledger line
 *                          ({@code PRODUCTION_ORDER})
 */
public record IssueReservedStockCommand(UUID reservationId, UUID orderId, UUID productionOrderId,
                                        String toLocation, UUID actorId) {

    public IssueReservedStockCommand {
        if (reservationId == null) throw new IllegalArgumentException("reservationId is required");
        if (productionOrderId == null) throw new IllegalArgumentException("productionOrderId is required");
        if (toLocation == null || toLocation.isBlank()) throw new IllegalArgumentException("toLocation is required");
    }
}
