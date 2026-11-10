package com.stockflow.order.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A customer's credit and what is left of it (kltn-docs 15 §4.3, BR-10; 18 §2): what Sales sees before sending a
 * contract that pays after delivery, and what the customer reads on the portal. Since 4B-10 there are no payment terms
 * in it: a contract's installment plan says how the customer pays.
 */
@Schema(name = "CreditPosition")
public record CreditPositionResponse(
        UUID customerId,
        @Schema(description = "Null without credit") BigDecimal creditLimit,
        @Schema(description = "Days to pay after delivery; null without credit") Integer creditTermDays,
        @Schema(description = "What the installments paid after delivery of the customer's signed contracts still owe, "
                + "not yet due included, and the cancellation fee items not paid (15 BR-10)") BigDecimal exposure,
        @Schema(description = "Limit minus exposure, never below zero; null without credit. A contract whose part paid "
                + "after delivery is above it is held for accounting when it is signed") BigDecimal availableCredit,
        String currency,
        @Schema(description = "An installment past its due date: a contract signed now is held for accounting")
        boolean hasOverdue,
        @Schema(description = "What is still unpaid on installments past their due date; zero when none")
        BigDecimal overdueAmount,
        @Schema(description = "The earliest due date among them; absent when nothing is overdue")
        LocalDate oldestOverdueDueDate
) {
}
