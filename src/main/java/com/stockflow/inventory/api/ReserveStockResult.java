package com.stockflow.inventory.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Result of a reservation: every hold it created, and what is left afterwards.
 *
 * <p><b>A list, not a single id.</b> An order line for 10 units of a SKU stocked as 6 + 4 across
 * two lots produces two reservations, because {@code StockItem} is keyed by (SKU, location, lot) and
 * one is a separate aggregate. Returning only the first would strand the second: cancelling the
 * order would release 6 units and leave 4 held until the sweeper expired them.</p>
 *
 * <p>A record rather than a bare list so that adding a field later — the promised ship date, say —
 * does not break every caller's signature.</p>
 */
public record ReserveStockResult(
        UUID orderId,
        List<StockReservation> reservations,
        int quantityReserved,
        int remainingAvailable,
        Instant reservedAt
) {

    public ReserveStockResult {
        if (reservations == null || reservations.isEmpty()) {
            throw new IllegalArgumentException("A reservation result must contain at least one hold");
        }
        reservations = List.copyOf(reservations);
    }

    /** The ids to pass back to {@link InventoryService#release} — all of them, not the first. */
    public List<UUID> reservationIds() {
        return reservations.stream().map(StockReservation::reservationId).toList();
    }
}
