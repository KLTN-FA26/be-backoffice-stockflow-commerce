package com.stockflow.inventory.consumption;

import java.util.Objects;
import java.util.UUID;

/**
 * One staged pick line to deduct when its task moves to dispatch (kltn-docs 07 §4.3 step 8; team plan 4C-5).
 *
 * @param reservationId the allocation the line staged with its carton
 * @param pickLineId    the line, and the idempotency key: one deduction per line
 */
public record StagedLine(UUID reservationId, UUID pickLineId) {

    public StagedLine {
        Objects.requireNonNull(reservationId, "reservationId");
        Objects.requireNonNull(pickLineId, "pickLineId");
    }
}
