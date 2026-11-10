package com.stockflow.procurement.internal.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Open a return receipt (team plan 4D-2; kltn-docs 03 §4.2 step 9): exactly one of {@code shipmentId} (a shipment the
 * carrier brought back) and {@code returnRequestId} (an approved return request), and the QUARANTINE area of that
 * warehouse the goods go into.
 */
public record CreateReturnReceiptRequest(
        UUID shipmentId,
        UUID returnRequestId,
        @NotBlank @Size(max = 64) String quarantineLocationCode,
        @Size(max = 2000) String note
) {
}
