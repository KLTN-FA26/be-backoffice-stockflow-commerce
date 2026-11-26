package com.stockflow.inventory.internal.domain;

import java.util.UUID;

/** One hold with the stock row it sits on, read without loading the stock item aggregate (SCRUM-267). */
public record HoldDetail(UUID reservationId, String sku, String locationCode, String lotNumber, int quantity,
                         ReservationStatus status, String lpn, boolean wholeCarton) {

    /** On loose stock. */
    public HoldDetail(UUID reservationId, String sku, String locationCode, String lotNumber, int quantity,
                      ReservationStatus status) {
        this(reservationId, sku, locationCode, lotNumber, quantity, status, null, false);
    }
}
