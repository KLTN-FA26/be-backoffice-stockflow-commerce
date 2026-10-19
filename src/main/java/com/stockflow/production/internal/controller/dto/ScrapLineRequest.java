package com.stockflow.production.internal.controller.dto;

import com.stockflow.production.internal.domain.ProductionRecords;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Spoilt units with their reason (BR-PRD-04); OTHER needs a note. */
public record ScrapLineRequest(
        @NotNull(message = "reason is required") ProductionRecords.ScrapReason reason,
        @Min(value = 1, message = "quantity is at least 1") int quantity,
        @Size(max = 1000, message = "note is at most 1000 characters") String note
) {
}
