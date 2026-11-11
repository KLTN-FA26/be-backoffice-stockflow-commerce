package com.stockflow.procurement.internal.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * One carton scanned on a return receipt (kltn-docs 03 §4.2 step 10): its LPN, and what the warehouse sees of its
 * outside - dented, wet, opened - when anything (03 edge cases; the carton is taken whole whatever it says).
 */
public record ScanReturnCartonRequest(
        @NotBlank @Size(max = 30) String lpn,
        @Size(max = 500) String condition
) {
}
