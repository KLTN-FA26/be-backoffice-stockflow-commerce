package com.stockflow.production.internal.controller.dto;

import com.stockflow.production.internal.domain.ProductionOrder;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Why work stops; OTHER needs a note. */
public record HoldRequest(
        @NotNull(message = "reason is required") ProductionOrder.HoldReason reason,
        @Size(max = 1000, message = "note is at most 1000 characters") String note
) {
}
