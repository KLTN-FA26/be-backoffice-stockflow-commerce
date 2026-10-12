package com.stockflow.inventory.internal.controller.dto;

import com.stockflow.inventory.internal.domain.TransferReason;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** The whole DRAFT transfer as the planner now wants it; its lines replace the old ones (SCRUM-462). */
@Schema(description = "Edit a DRAFT transfer order")
public record UpdateTransferOrderRequest(
        @Schema(description = "The version read with the transfer; a transfer changed since answers 409 OPTIMISTIC_LOCK")
        @NotNull(message = "version is required") Long version,
        @NotNull(message = "fromWarehouseId is required") UUID fromWarehouseId,
        @NotNull(message = "toWarehouseId is required") UUID toWarehouseId,
        TransferReason reason,
        LocalDate expectedDate,
        @NotEmpty(message = "A transfer order needs at least one line")
        @Size(max = 200, message = "At most 200 lines")
        List<CreateTransferOrderRequest.@Valid @NotNull Line> lines
) {
}
