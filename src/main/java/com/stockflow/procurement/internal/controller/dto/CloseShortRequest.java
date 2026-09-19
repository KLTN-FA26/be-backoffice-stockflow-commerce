package com.stockflow.procurement.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Close a purchase order short, writing off the remaining open quantity")
public record CloseShortRequest(

        @NotBlank(message = "reason is required")
        String reason
) {
}
