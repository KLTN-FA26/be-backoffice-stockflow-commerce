package com.stockflow.production.internal.controller.dto;

import com.stockflow.common.validation.ValidSku;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.UUID;

@Schema(description = "What a sample round prints: a confirmed design of the customer on a blank")
public record SampleDraftRequest(
        @NotNull(message = "designSnapshotId is required") UUID designSnapshotId,
        @Schema(example = "CUP-PP-500")
        @NotBlank(message = "blankSku is required") @ValidSku String blankSku,
        @Min(value = 1, message = "quantity must be at least 1")
        @Max(value = 1000, message = "quantity is at most 1,000") int quantity,
        LocalDate dueDate
) {
}
