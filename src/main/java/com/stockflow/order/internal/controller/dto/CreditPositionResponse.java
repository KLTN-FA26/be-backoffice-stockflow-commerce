package com.stockflow.order.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
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
        String currency,
        @Schema(description = "An overdue receivable: new credit orders wait for the credit approver")
        boolean hasOverdue,
        @Schema(example = "BEFORE_DELIVERY", description = "How a deposit order's balance is collected: "
                + "BEFORE_DELIVERY (by transfer, before the goods leave), ON_DELIVERY (the carrier collects it) or "
                + "ON_CREDIT (a receivable on the customer's credit); null without deposits (G2-3)")
        String depositBalancePolicy,
        @Schema(description = "What is still unpaid on receivables past their due date; zero when none (SCRUM-193)")
        BigDecimal overdueAmount,
        @Schema(description = "The earliest due date among the overdue receivables; absent when nothing is overdue")
        LocalDate oldestOverdueDueDate,
        @Schema(example = "[\"BEFORE_DELIVERY\",\"ON_CREDIT\"]", description = "The balance policies a deposit "
                + "order of this customer may be placed on (the balancePolicy of POST /orders): empty without deposits "
                + "(SCRUM-193)")
        List<String> allowedBalancePolicies
) {
}
