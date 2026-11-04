package com.stockflow.inventory.api;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * kltn-docs 19 BR-10 (decision 2026-10-19: a cancellation after printing is allowed, with a production fee, and the
 * printed cups are SCRAP): the printed goods of one print line of {@code orderId} that will not ship - the whole line
 * when the order is cancelled, the closed units when its unshipped rest is closed - are written off.
 *
 * @param requestId      the caller's idempotency key for this one cancellation or closure of the line
 * @param reservationIds the line's holds; holds of other lines, other orders or never printed are skipped
 * @param quantity       the units that will not ship
 * @param requestedBy    who cancelled or closed; null (the system) asks for nothing
 */
public record ScrapCancelledPrintCommand(UUID orderId, UUID requestId, List<UUID> reservationIds, int quantity,
                                         String note, UUID requestedBy) {

    public ScrapCancelledPrintCommand {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(requestId, "requestId");
        reservationIds = List.copyOf(reservationIds);
        if (quantity < 0) {
            throw new IllegalArgumentException("quantity must not be negative");
        }
    }
}
