package com.stockflow.inventory.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(name = "AcknowledgeAlertRequest")
public record AcknowledgeAlertRequest(

        @Schema(description = "Optional remark, up to 500 characters; shown on the alert, never sent in notifications")
        @Size(max = 500)
        String note,

        @Schema(description = "The alert's version as the client read it")
        @NotNull
        Long version
) {
}
