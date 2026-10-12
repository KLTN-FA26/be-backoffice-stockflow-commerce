package com.stockflow.production.internal.controller.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * A QC round over every good unit waiting for it. {@code packingLocation}: where an ORDER's goods go when this
 * round completes it; empty for the warehouse's only PACKING area.
 */
public record QcRequest(
        @Min(value = 0, message = "passed cannot be negative") int passed,
        @Min(value = 0, message = "failed cannot be negative") int failed,
        @Size(max = 1000, message = "note is at most 1000 characters") String note,
        @Size(max = 64, message = "packingLocation is at most 64 characters") String packingLocation
) {
}
