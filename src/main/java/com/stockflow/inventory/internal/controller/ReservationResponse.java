package com.stockflow.inventory.internal.controller;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What the caller gets back after a successful reservation.
 *
 * <p>Mirrors {@code ReserveStockResult} component for component, which is what lets MapStruct
 * generate the mapping — including the nested list — without a single {@code @Mapping}. Keeping
 * the two shapes aligned is deliberate: the moment they diverge, the compile fails rather than a
 * field silently arriving as null.</p>
 */
@Schema(name = "Reservation", description = "Stock held for one order line")
public record ReservationResponse(

        UUID orderId,

        @Schema(description = "One hold per lot the line was drawn from - pass every id back to release")
        List<Hold> holds,

        int quantityReserved,

        @Schema(description = "Units of this SKU still available across all locations after the hold")
        int remainingAvailable,

        Instant reservedAt
) {

    @Schema(name = "StockReservation", description = "One hold, on one stock item")
    public record Hold(
            UUID reservationId,
            UUID stockItemId,

            @Schema(example = "HCM-A-01-02-B")
            String locationCode,

            @Schema(description = "Lot the units come from; null when the product is not lot-tracked")
            String lotNumber,

            int quantity
    ) {
    }
}
