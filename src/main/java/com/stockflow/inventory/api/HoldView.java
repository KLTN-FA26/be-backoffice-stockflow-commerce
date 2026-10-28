package com.stockflow.inventory.api;

import java.time.Instant;
import java.util.UUID;

/**
 * One hold, read by id: what it keeps back, where and from which lot, and whether it is still live
 * (SCRUM-267). Fulfillment turns each live hold of an order's stock lines into one pick line: the
 * picker walks to {@code locationCode} and takes {@code quantity} of {@code lotNumber}.
 *
 * @param expiresAt  null once pinned: the hold no longer waits for payment
 * @param expiryDate the goods' own expiry (their lot's), null for a SKU without one; past it they cannot move to a
 *                   storage, packing or print-shop location, only be written off
 * @param serialNumber the unit's serial number for a serial-tracked SKU (one unit per stock row), else null; packing
 *                     checks a scanned serial against it (kltn-docs 08 step 2)
 * @param lpn          the carton the held units are in (SCRUM-473), null for loose stock or a SKU reservation: the
 *                     picker scans it, since the hold is deducted from that carton and no other
 * @param wholeCarton  the hold is its carton's whole, sealed content: the picker takes the carton as it stands
 */
public record HoldView(UUID reservationId, String sku, String locationCode, String lotNumber, int quantity,
                       Status status, Instant expiresAt, java.time.LocalDate expiryDate, String serialNumber,
                       String lpn, boolean wholeCarton) {

    /** Without the serial number: goods that have none. */
    public HoldView(UUID reservationId, String sku, String locationCode, String lotNumber, int quantity,
                    Status status, Instant expiresAt, java.time.LocalDate expiryDate) {
        this(reservationId, sku, locationCode, lotNumber, quantity, status, expiresAt, expiryDate, null);
    }

    /** On loose stock, or a SKU reservation. */
    public HoldView(UUID reservationId, String sku, String locationCode, String lotNumber, int quantity,
                    Status status, Instant expiresAt, java.time.LocalDate expiryDate, String serialNumber) {
        this(reservationId, sku, locationCode, lotNumber, quantity, status, expiresAt, expiryDate, serialNumber,
                null, false);
    }


    /**
     * The hold's lifecycle; only {@code HELD} still keeps stock back. {@code ALLOCATED} is a SKU reservation whose
     * units moved to allocations (SCRUM-152): its {@code locationCode}, {@code lotNumber} and {@code expiryDate}
     * are null, as they are for a SKU reservation still HELD — it was never on a shelf.
     */
    public enum Status { HELD, ALLOCATED, CONSUMED, RELEASED, EXPIRED }
}
