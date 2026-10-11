package com.stockflow.procurement.internal.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One part of a QC decision on a receipt line: so many units accepted, quarantined or rejected.
 *
 * @param targetLocationId where quarantined or rejected units went; null for accepted ones, which
 *                         stay in the QC area until putaway
 */
public record QcInspection(
        UUID id,
        QcOutcome outcome,
        int quantity,
        UUID targetLocationId,
        String reason,
        UUID inspectedBy,
        Instant inspectedAt
) {

    public QcInspection {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(inspectedBy, "inspectedBy");
        Objects.requireNonNull(inspectedAt, "inspectedAt");
        if (quantity <= 0) {
            throw new IllegalArgumentException("An inspection covers a positive quantity");
        }
    }
}
