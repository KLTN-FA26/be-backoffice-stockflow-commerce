package com.stockflow.inventory.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A warehouse manager quarantines a carton (kltn-docs 20 §4.8 step 1, Input "Cách ly thùng lỗi": the carton, a
 * destination in a QUARANTINE area, a reason).
 *
 * @param locationCode a QUARANTINE area of the carton's warehouse
 * @param reason       why; recorded on the ledger line
 */
@Schema(description = "Quarantine a whole carton: where it goes and why")
public record QuarantineStockPackageRequest(

        @Schema(example = "HCM-QC01", description = "A QUARANTINE area of the carton's warehouse")
        @NotBlank(message = "locationCode is required")
        @Size(max = 64, message = "locationCode is at most 64 characters")
        String locationCode,

        @Schema(example = "Carton crushed on one side")
        @NotBlank(message = "reason is required")
        @Size(max = 500, message = "reason is at most 500 characters")
        String reason
) {
}
