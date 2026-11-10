package com.stockflow.inventory.consumption;

import java.util.Objects;
import java.util.UUID;

/**
 * Put the carton of one picked line back onto a bin, whole (kltn-docs 07 §4.5 step 14, BR-05; team plan 4C-4).
 *
 * @param pickLineId    the line, and the idempotency key: one put-back per line
 * @param reservationId the hold the line consumed: it names the carton's stock row, which takes its units back
 * @param lpn           the carton the line took, as the pick line names it; checked against the hold's carton
 * @param locationCode  the bin the carton goes onto
 */
public record PutBackCommand(UUID pickLineId, UUID reservationId, String lpn, String locationCode, UUID actorId) {

    public PutBackCommand {
        Objects.requireNonNull(pickLineId, "pickLineId");
        Objects.requireNonNull(reservationId, "reservationId");
        Objects.requireNonNull(lpn, "lpn");
        Objects.requireNonNull(locationCode, "locationCode");
    }
}
