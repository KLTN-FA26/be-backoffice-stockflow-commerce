package com.stockflow.inventory.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

@Schema(description = "Recount lines whose variance adjustment was rejected; opens a new blind count")
public record RecountRequest(

        @NotEmpty(message = "lineIds is required")
        @Size(max = 500, message = "lineIds holds at most 500 lines")
        List<@NotNull UUID> lineIds,

        @Size(max = 500, message = "note is at most 500 characters")
        String note
) {
}
