package com.stockflow.inventory.internal.domain;

import java.time.Instant;
import java.util.UUID;

/** One hold with the stock row it sits on, read without loading the stock item aggregate (SCRUM-267). */
public record HoldDetail(UUID reservationId, String sku, String locationCode, String lotNumber, int quantity,
                         ReservationStatus status, Instant expiresAt, java.time.LocalDate expiryDate,
                         String serialNumber, String lpn, boolean wholeCarton) {

    public HoldDetail(UUID reservationId, String sku, String locationCode, String lotNumber, int quantity,
                      ReservationStatus status, Instant expiresAt, java.time.LocalDate expiryDate) {
        this(reservationId, sku, locationCode, lotNumber, quantity, status, expiresAt, expiryDate, null);
    }

    /** On loose stock. */
    public HoldDetail(UUID reservationId, String sku, String locationCode, String lotNumber, int quantity,
                      ReservationStatus status, Instant expiresAt, java.time.LocalDate expiryDate,
                      String serialNumber) {
        this(reservationId, sku, locationCode, lotNumber, quantity, status, expiresAt, expiryDate, serialNumber,
                null, false);
    }
}
