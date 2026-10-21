package com.stockflow.order.internal.controller.dto;

import com.stockflow.order.api.CancellationReasonCode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Close what is left of a partly fulfilled order (G3-2, split shipments): the units not shipped will not be delivered.
 *
 * @param note required for OTHER
 */
@Schema(description = "Close the rest of a partly fulfilled order")
public record CloseRemainderRequest(

        @Schema(example = "OUT_OF_STOCK")
        @NotNull(message = "reasonCode is required")
        CancellationReasonCode reasonCode,

        @Size(max = 1000, message = "note is at most 1000 characters")
        String note
) {
}
