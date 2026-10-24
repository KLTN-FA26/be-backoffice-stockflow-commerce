package com.stockflow.production.internal.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Completing a job with fewer good units than planned (S6). {@code reason}: why, as the manager records it (the blanks
 * ran out, a reprint would be too late) - kept on the job and its history; the customer's agreement is Sales', on the
 * order line. {@code packingLocation}: where the good units go; empty for the warehouse's only PACKING area.
 */
public record CompleteShortRequest(
        @NotBlank(message = "reason is required") @Size(max = 500, message = "reason is at most 500 characters")
        String reason,
        @Size(max = 64, message = "packingLocation is at most 64 characters")
        String packingLocation
) {
}
