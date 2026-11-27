package com.stockflow.assistant.internal.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Installments overdue or due within the week, the ones to chase first on top. */
public record CollectionsResponse(
        List<ItemResponse> items,
        @Schema(description = "How many installments are overdue or due within the week") int total,
        @Schema(description = "Whether items shows fewer than total: the list keeps the ones to chase first")
        boolean truncated) {

    /** One installment to chase. */
    public record ItemResponse(
            UUID installmentId,
            String contractNumber,
            String orderNumber,
            PartyResponse customer,
            @JsonInclude(JsonInclude.Include.ALWAYS)
            @Schema(description = "Who answers for the contract: who sent it, else who recorded it signed",
                    nullable = true) PartyResponse salesperson,
            @Schema(description = "The installment's name in the contract's plan", example = "Đợt 2") String label,
            @Schema(description = "What it still owes, whole VND") BigDecimal amountDue,
            LocalDate dueDate,
            @Schema(description = "Days past the due date it is still owing; 0 when not yet due") int daysOverdue,
            @Schema(allowableValues = {"HIGH", "MEDIUM", "LOW"}) String priority,
            @Schema(description = "Why it ranks where it does, as sentences in the caller's language")
            List<String> why,
            @Schema(example = "/admin/payment/installments/018f...") String href) {
    }

    /** A customer or a staff member, by id and name. */
    public record PartyResponse(
            UUID id,
            @JsonInclude(JsonInclude.Include.ALWAYS) @Schema(nullable = true) String name) {
    }
}
