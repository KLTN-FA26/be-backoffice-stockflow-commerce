package com.stockflow.procurement.internal.controller.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * A QC decision on one receipt line: every unit moved to QC is accepted, quarantined or rejected
 * (BR-08). Quarantined and rejected parts name the QUARANTINE area they go to, and why. A part of
 * 0 units needs neither; a part with units missing them is QC_TARGET_LOCATION_REQUIRED /
 * QC_REASON_REQUIRED on {@code quarantined.locationCode}, {@code rejected.reason}… (SCRUM-464).
 *
 * <p>SCRUM-473: a part's {@code quantity} is its loose units, and {@code packages} the LPNs of the
 * line's cartons it takes whole; absent or empty, the part is loose units only, as before.</p>
 */
public record QcDecisionRequest(
        @PositiveOrZero int accepted,
        @Valid Part quarantined,
        @Valid Part rejected
) {

    public record Part(
            @PositiveOrZero int quantity,
            @Size(max = 64) String locationCode,
            @Size(max = 500) String reason,
            @Size(max = 500) List<@NotBlank @Size(max = 30) String> packages
    ) {
    }
}
