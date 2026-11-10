package com.stockflow.inventory.api;

import java.time.Instant;
import java.util.UUID;

/**
 * One hold, read by id: what it keeps back, where and from which lot, and whether it is still live
 * (SCRUM-267). Fulfillment turns each live hold of an order's stock lines into one pick line: the
 * picker walks to {@code locationCode} and takes {@code quantity} of {@code lotNumber}.
 *
 * @param expiresAt  null once pinned: the hold no longer waits for payment. The hold's deadline, not the goods':
 *                   cups and packaging have no expiry date (kltn-docs 01 BR-17)
 * @param lpn          the carton the held units are in (SCRUM-473), null for loose stock or a SKU reservation: the
 *                     picker scans it, since the hold is deducted from that carton and no other
 * @param wholeCarton  the hold is its carton's whole, sealed content: the picker takes the carton as it stands
 *
 * <p>No serial number: nothing is serial-tracked (kltn-docs 01 BR-17, team plan 5-5).</p>
 */
public record HoldView(UUID reservationId, String sku, String locationCode, String lotNumber, int quantity,
                       Status status, Instant expiresAt, String lpn, boolean wholeCarton) {

    /** On loose stock, or a SKU reservation. */
    public HoldView(UUID reservationId, String sku, String locationCode, String lotNumber, int quantity,
                    Status status, Instant expiresAt) {
        this(reservationId, sku, locationCode, lotNumber, quantity, status, expiresAt, null, false);
    }


    /**
     * The hold's lifecycle; only {@code HELD} still keeps stock back. {@code ALLOCATED} is a SKU reservation whose
     * units moved to allocations (SCRUM-152): its {@code locationCode} and {@code lotNumber} are null, as they are for a SKU reservation still HELD — it was never on a shelf.
     */
    public enum Status { HELD, ALLOCATED, CONSUMED, RELEASED, EXPIRED }
}
