package com.stockflow.production.internal.controller.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 03 §16 G9 "in bù nội bộ": {@code quantity} of what the subcontractor still owes goes to a new in-house production
 * order. {@code packingLocation}: where the subcontracted order's good units go if this completes it; empty for the
 * warehouse's only PACKING area.
 */
public record SubcontractInHouseRequest(
        @Min(value = 1, message = "quantity is at least 1") int quantity,
        @NotBlank(message = "reason is required") @Size(max = 500, message = "reason is at most 500 characters")
        String reason,
        @Size(max = 64, message = "packingLocation is at most 64 characters") String packingLocation
) {
}
