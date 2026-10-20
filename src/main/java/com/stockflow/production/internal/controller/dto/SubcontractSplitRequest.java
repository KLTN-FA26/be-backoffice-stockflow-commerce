package com.stockflow.production.internal.controller.dto;

import com.stockflow.production.internal.domain.ProductionOrder;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Split part of a production order off to a print subcontractor (SCRUM-441). */
public record SubcontractSplitRequest(
        @Min(value = 1, message = "quantity is at least 1") int quantity,
        @NotNull(message = "subcontractorId is required") UUID subcontractorId,
        @NotNull(message = "method is required") ProductionOrder.SubcontractMethod method,
        @NotNull(message = "unitPrice is required") @DecimalMin(value = "0", message = "unitPrice is not negative")
        BigDecimal unitPrice,
        @Pattern(regexp = "[A-Z]{3}", message = "currency is a 3-letter code") String currency,
        LocalDate dueDate
) {
}
