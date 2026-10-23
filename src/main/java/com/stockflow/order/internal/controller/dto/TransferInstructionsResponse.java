package com.stockflow.order.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * How a customer pays an order by bank transfer (SCRUM-193, kltn-docs 15 §4.1): the company's account, the content to
 * write, and what is still due now.
 */
@Schema(name = "TransferInstructions")
public record TransferInstructionsResponse(
        UUID orderId,
        String orderNumber,
        @Schema(example = "Vietcombank") String bankName,
        @Schema(example = "0071000888999") String accountNumber,
        @Schema(example = "CONG TY TNHH STOCKFLOW") String accountName,
        @Schema(example = "SO-20261023-0007", description = "What to write as the transfer content: the order number, "
                + "so the accountant can match the statement line to the order")
        String transferReference,
        @Schema(description = "Still due now, never below zero: the deposit (or prepayment) while it is unpaid, else "
                + "the balance due before delivery, else 0 (credit, balance paid on delivery or on credit, all paid)")
        BigDecimal amountDue,
        @Schema(example = "VND") String currency,
        @Schema(example = "TO_CONFIRM", description = "TO_CONFIRM (the order waits for it), BEFORE_DELIVERY (the goods "
                + "leave once it is in) or NONE (amountDue is 0)")
        String dueBy,
        @Schema(description = "dueBy in words, in the caller's language") String dueByNote
) {
}
