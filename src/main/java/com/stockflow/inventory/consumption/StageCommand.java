package com.stockflow.inventory.consumption;

import java.util.Objects;
import java.util.UUID;

/**
 * Move the carton one pick line takes from its bin to the task's staging area, whole, still held for the order
 * (kltn-docs 07 §4.2 step 5 "STAGING", 20 §4.9 step 1; team plan 4C-5).
 *
 * @param pickLineId          the line, and the idempotency key: one staging per line
 * @param reservationId       the allocation the line takes: it names the carton's stock row, and moves with it
 * @param lpn                 the carton, as the pick line names it; checked against the allocation's carton
 * @param stagingLocationCode the staging area of the task's warehouse the carton goes to
 */
public record StageCommand(UUID pickLineId, UUID reservationId, String lpn, String stagingLocationCode, UUID actorId) {

    public StageCommand {
        Objects.requireNonNull(pickLineId, "pickLineId");
        Objects.requireNonNull(reservationId, "reservationId");
        Objects.requireNonNull(lpn, "lpn");
        Objects.requireNonNull(stagingLocationCode, "stagingLocationCode");
    }
}
