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
 */
public record HoldView(UUID reservationId, String sku, String locationCode, String lotNumber, int quantity,
                       Status status, Instant expiresAt, java.time.LocalDate expiryDate) {

    /** The hold's lifecycle; only {@code HELD} still keeps stock back. */
    public enum Status { HELD, CONSUMED, RELEASED, EXPIRED }
}
