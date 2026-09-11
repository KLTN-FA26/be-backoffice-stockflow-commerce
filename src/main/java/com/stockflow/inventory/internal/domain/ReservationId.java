package com.stockflow.inventory.internal.domain;

import java.util.UUID;

/**
 * Identifier of a single {@link Reservation} held inside a {@link StockItem}.
 *
 * <p>This id is the one internal value that does leave the module, as the {@code reservationId}
 * field of {@code ReserveStockResult} — {@code order} stores it and hands it back to
 * {@code InventoryService.release(...)} later. It leaves as a plain {@code UUID}, unwrapped, so
 * that no other module has to import anything from {@code inventory.internal}.</p>
 */
public record ReservationId(UUID value) {

    public ReservationId {
        if (value == null) {
            throw new IllegalArgumentException("ReservationId must not be null");
        }
    }

    public static ReservationId newId() {
        return new ReservationId(UUID.randomUUID());
    }

    public static ReservationId of(UUID raw) {
        return new ReservationId(raw);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
