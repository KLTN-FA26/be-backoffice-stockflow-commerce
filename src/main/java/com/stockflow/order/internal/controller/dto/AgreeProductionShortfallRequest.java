package com.stockflow.order.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The customer agreed with Sales to go without units of a print line production cannot finish (review 2026-10-25 of
 * S6): production completes the line's job short within it.
 *
 * @param quantity the most units the customer goes without; they keep at least one. 0 withdraws the agreement while
 *                 nothing was closed under it
 * @param note     how the agreement was given - the call, the message, its date; or why it is withdrawn
 */
@Schema(description = "The customer's agreement to take a print line short")
public record AgreeProductionShortfallRequest(

        @Schema(example = "60")
        @NotNull(message = "quantity is required")
        @Min(value = 0, message = "quantity is at least 0")
        Integer quantity,

        @Schema(example = "Khách đồng ý nhận thiếu tối đa 60 ly, không chờ in bù (Zalo 25/10)")
        @NotBlank(message = "note is required")
        @Size(max = 500, message = "note is at most 500 characters")
        String note
) {
}
