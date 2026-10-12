package com.stockflow.procurement.internal.controller.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * A QC decision on one receipt line: every unit moved to QC is accepted, quarantined or rejected
 * (BR-08). Quarantined and rejected parts name the QUARANTINE area they go to, and why. A part of
 * 0 units needs neither; a part with units missing them is QC_TARGET_LOCATION_REQUIRED /
 * QC_REASON_REQUIRED on {@code quarantined.locationCode}, {@code rejected.reason}… (SCRUM-464).
 */
public record QcDecisionRequest(
        @PositiveOrZero int accepted,
        @Valid Part quarantined,
        @Valid Part rejected
) {

    public record Part(
            @PositiveOrZero int quantity,
            @Size(max = 64) String locationCode,
            @Size(max = 500) String reason
    ) {
    }
}
