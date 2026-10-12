package com.stockflow.production.internal.controller.dto;

import com.stockflow.common.validation.ValidSku;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.UUID;

@Schema(description = "A first sample round for a customer, drafted by Sales")
public record CreateSampleRequestRequest(
        @NotNull(message = "customerId is required") UUID customerId,
        @Schema(description = "Optional: an open quote (DRAFT, SENT, CHANGES_REQUESTED) of this customer that quotes this design on this blank") UUID quoteId,
        @NotNull(message = "designSnapshotId is required") UUID designSnapshotId,
        @Schema(example = "CUP-PP-500")
        @NotBlank(message = "blankSku is required") @ValidSku String blankSku,
        @Min(value = 1, message = "quantity must be at least 1")
        @Max(value = 1000, message = "quantity is at most 1,000") int quantity,
        LocalDate dueDate
) {
}
