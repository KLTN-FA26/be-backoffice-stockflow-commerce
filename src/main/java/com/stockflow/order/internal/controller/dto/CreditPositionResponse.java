package com.stockflow.order.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * The payment terms a customer may use and the credit left (SCRUM-427, SCRUM-193): what the checkout
 * offers, and what Sales sees before proposing a credit order.
 */
@Schema(name = "CreditPosition")
public record CreditPositionResponse(
        UUID customerId,
        @Schema(example = "[\"PREPAID\",\"CREDIT\"]") List<String> allowedTerms,
        @Schema(example = "CREDIT") String defaultTerm,
        BigDecimal depositPercent,
        BigDecimal creditLimit,
        Integer creditTermDays,
        @Schema(description = "Owed on credit orders not yet delivered") BigDecimal exposure,
        @Schema(description = "Limit minus exposure; null without credit. An order above it waits for approval")
        BigDecimal availableCredit,
        String currency
) {
}
