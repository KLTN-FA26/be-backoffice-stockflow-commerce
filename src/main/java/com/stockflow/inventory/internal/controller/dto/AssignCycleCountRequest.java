package com.stockflow.inventory.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

@Schema(description = "Hand a planned count to an active warehouse staff member or manager of its warehouse")
public record AssignCycleCountRequest(

        @NotNull(message = "assignedUserId is required")
        UUID assignedUserId
) {
}
