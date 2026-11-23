package com.stockflow.inventory.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The warehouse manager carries out the customer's choice for a carton held for them (WF-05 Q-4, TH-6).
 *
 * @param note the record of it: the hand-over note, or the destruction record the customer agreed to
 */
@Schema(description = "Hand back or destroy a carton held for the customer, as they chose")
public record EndCustodyRequest(

        @Schema(example = "Hand-over record 07/2026, collected by the customer's truck")
        @NotBlank(message = "note is required")
        @Size(max = 500, message = "note is at most 500 characters")
        String note
) {
}
