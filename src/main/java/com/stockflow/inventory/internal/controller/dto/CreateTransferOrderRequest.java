package com.stockflow.inventory.internal.controller.dto;

import com.stockflow.inventory.internal.domain.TransferReason;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Schema(description = "A transfer of stock from one warehouse to another")
public record CreateTransferOrderRequest(
        @NotNull(message = "fromWarehouseId is required") UUID fromWarehouseId,
        @NotNull(message = "toWarehouseId is required") UUID toWarehouseId,
        TransferReason reason,
        LocalDate expectedDate,
        @NotEmpty(message = "A transfer order needs at least one line")
        @Size(max = 200, message = "At most 200 lines")
        List<@Valid Line> lines
) {

    public record Line(
            @Schema(example = "CUP-PP-500")
            @NotBlank(message = "sku is required")
            @Pattern(regexp = "[A-Za-z0-9\\-]{3,64}", message = "malformed SKU")
            String sku,
            @Size(max = 64, message = "lotNumber is at most 64 characters")
            String lotNumber,
            @Min(value = 1, message = "quantity must be at least 1")
            @Max(value = 1_000_000, message = "quantity is at most 1,000,000")
            int quantity
    ) {
    }
}
