package com.stockflow.assistant.internal.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Where to look for a bank transfer waiting for review: at most three installments it most likely pays, best first. It
 * only points; the Accountant assigns the transfer on its own screen.
 */
public record MatchSuggestionsResponse(
        TransactionResponse transaction,
        List<SuggestionResponse> suggestions,
        @Schema(description = "The best two score the same: the figures do not say which it pays, so best first is no "
                + "preference between them") boolean ambiguous) {

    /** The transfer as kept. */
    public record TransactionResponse(
            UUID id,
            @Schema(description = "Suggestions are made only for NEEDS_REVIEW; any other status has none",
                    allowableValues = {"RECEIVED", "APPLIED", "NEEDS_REVIEW", "IGNORED"}) String status,
            @Schema(description = "What it brought, whole VND") BigDecimal amount,
            @Schema(description = "When the bank booked it: when the money came in") Instant transactionAt,
            @JsonInclude(JsonInclude.Include.ALWAYS) @Schema(nullable = true) String content,
            @JsonInclude(JsonInclude.Include.ALWAYS)
            @Schema(description = "Why the bank feed left it for review", nullable = true, allowableValues = {
                    "NO_INSTALLMENT_NAMED", "SEVERAL_INSTALLMENTS", "INSTALLMENT_NOT_FOUND", "NOT_PAYABLE",
                    "OVERPAYMENT", "OTHER_ACCOUNT", "REFUSED", "RECEIPT_VOIDED"}) String reviewReason) {
    }

    /** One installment the transfer may pay, with why. */
    public record SuggestionResponse(
            UUID installmentId,
            String contractNumber,
            String orderNumber,
            @JsonInclude(JsonInclude.Include.ALWAYS) @Schema(nullable = true) String customerName,
            @Schema(description = "The installment's name in the contract's plan", example = "Đợt 2") String label,
            @Schema(description = "The installment's transfer reference, what the content should have said",
                    example = "HD-20261117-000123-1 DOT2") String transferReference,
            @Schema(description = "What it still owes, whole VND") BigDecimal amountDue,
            @JsonInclude(JsonInclude.Include.ALWAYS)
            @Schema(description = "Null while its milestone has not come", nullable = true) LocalDate dueDate,
            @Schema(description = "0 to 100: the sum of the reasons' weights, capped") int score,
            @Schema(description = "The signals that scored it", allowableValues = {"CONTRACT_NUMBER_IN_CONTENT",
                    "ORDER_NUMBER_IN_CONTENT", "INSTALLMENT_IN_CONTENT", "TAX_CODE_IN_CONTENT",
                    "CUSTOMER_NAME_IN_CONTENT", "AMOUNT_EXACT", "AMOUNT_WITHIN_DUE", "AMOUNT_ABOVE_DUE", "OVERDUE",
                    "DUE_SOON"})
            List<String> reasons,
            @Schema(example = "/admin/payment/installments/018f...") String href) {
    }
}
