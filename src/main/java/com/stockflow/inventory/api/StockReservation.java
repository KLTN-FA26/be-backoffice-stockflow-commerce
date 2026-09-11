package com.stockflow.inventory.api;

import java.util.UUID;

/**
 * One hold, on one stock item, created by a reservation.
 *
 * <p>Its existence is the consequence of a fact about warehouses: a single order line is often
 * drawn from more than one lot or location. Ten units may be six from an older lot and four from a
 * newer one, and each of those is a separate hold on a separate stock item — because that is what
 * the picker will physically walk to.</p>
 *
 * <p>The caller stores every id and hands them all back when releasing. Keeping only "the first
 * one" would leave the remaining reservations stranded until the sweeper expired them, which reads
 * stock mysteriously unavailable for half an hour after a cancellation.</p>
 */
public record StockReservation(
        UUID reservationId,
        UUID stockItemId,
        String locationCode,
        String lotNumber,
        int quantity
) {
}
