package com.stockflow.inventory.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

@Schema(description = "What left the source: units per line. A line not listed ships nothing.")
public record DispatchTransferRequest(
        @NotEmpty(message = "List the shipped quantity of at least one line") List<@Valid Line> lines
) {

    public record Line(@NotNull(message = "lineId is required") UUID lineId,
                       @Min(value = 0, message = "shippedQuantity must not be negative") int shippedQuantity) {
    }
}
