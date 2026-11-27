package com.stockflow.assistant.internal.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A quote's prices beside what the customer pays elsewhere and was last sold, and its credit (contract §1.4). The
 * quote's own price check still decides whether it needs approval.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record PriceReviewResponse(
        UUID quoteId,
        String quoteNumber,
        @Schema(example = "DRAFT") String quoteStatus,
        Customer customer,
        List<Line> lines,
        @Schema(description = "Null when the caller may not read the customer's credit (customer-credit-profiles:READ)")
        Credit credit,
        @Schema(allowableValues = {"LINES_BELOW_REFERENCE", "LINES_WITHOUT_REFERENCE", "LINES_BELOW_LAST_SOLD",
                "CREDIT_OVERDUE"})
        List<String> flags) {

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Customer(UUID id, String name, String code) {
    }

    /** One quote line, every price per piece, read at the line's proposed cartons. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Line(
            int lineNo,
            String sku,
            Integer cartons,
            Integer unitsPerCarton,
            @Schema(description = "Null while the line is not priced") BigDecimal quotedUnitPrice,
            @Schema(description = "The customer's own price at these cartons") BigDecimal customerPrice,
            @Schema(description = "The standard price of the SKU's variant at these cartons") BigDecimal standardPrice,
            @Schema(description = "On the customer's latest order of the SKU with a signed contract")
            BigDecimal lastSoldUnitPrice,
            @Schema(description = "The day that contract was signed") LocalDate lastSoldOn,
            String lastSoldOrderNumber,
            @Schema(description = "(quoted - reference) / reference x 100, two decimals; negative is below")
            BigDecimal deltaVsReference,
            @Schema(allowableValues = {"CUSTOMER_PRICE", "STANDARD_PRICE", "LAST_SOLD"}) String reference,
            @Schema(allowableValues = {"BELOW_REFERENCE", "NO_REFERENCE", "BELOW_LAST_SOLD", "NOT_PRICED",
                    "LAST_SOLD_OTHER_PACK"})
            List<String> flags) {
    }

    /** As the customer's credit position screen shows it. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Credit(
            @Schema(description = "Null for a customer without credit") BigDecimal limit,
            @Schema(description = "Owed after delivery, not yet due included") BigDecimal outstanding,
            BigDecimal overdue,
            @Schema(description = "The limit less what is owed, never below zero; null without a limit")
            BigDecimal available,
            LocalDate oldestOverdueDueDate,
            String currency) {
    }
}
