package com.stockflow.inventory.consumption;

import java.util.Objects;
import java.util.UUID;

/**
 * Put the goods of one picked line back onto a bin.
 *
 * @param pickLineId    the line, and the idempotency key: one put-back per line
 * @param reservationId the hold the line consumed; names the stock layer the goods came from, whose lot,
 *                      expiry and receipt time they keep
 * @param locationCode  the bin the goods go onto
 * @param quantity      everything the line picked
 */
public record PutBackCommand(UUID pickLineId, UUID reservationId, String sku, String lotNumber,
                             String locationCode, int quantity, UUID actorId) {

    public PutBackCommand {
        Objects.requireNonNull(pickLineId, "pickLineId");
        Objects.requireNonNull(reservationId, "reservationId");
        Objects.requireNonNull(sku, "sku");
        Objects.requireNonNull(locationCode, "locationCode");
        if (quantity <= 0) {
            throw new IllegalArgumentException("A put-back returns a positive quantity");
        }
    }
}
