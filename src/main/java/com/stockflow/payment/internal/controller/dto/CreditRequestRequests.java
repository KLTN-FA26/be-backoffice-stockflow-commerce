package com.stockflow.payment.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

/** The bodies of the credit request endpoints (QĐ-A, 2026-11-26). */
public final class CreditRequestRequests {

    private CreditRequestRequests() {
    }

    /**
     * Ask for a credit limit. From the portal the customer asks for themselves: {@code customerId}, {@code salesNote}
     * and {@code recommendedLimit} are not theirs to send and are ignored. Sales ask for {@code customerId}, with
     * their opinion ({@code salesNote}, required).
     */
    @Schema(description = "A credit request")
    public record File(
            @Schema(description = "Back office only: the customer asked for; required there, ignored from the portal")
            UUID customerId,
            @NotNull @Positive @Schema(example = "500000000", description = "The limit asked, above zero (VND)")
            BigDecimal requestedLimit,
            @NotNull @Min(0) @Max(365) @Schema(example = "30", description = "Days to pay after delivery, 0-365")
            Integer requestedTermDays,
            @Size(max = 1000) @Schema(description = "Why the customer needs it; optional") String reason,
            @Size(max = 1000) @Schema(description = "Back office only: Sales' opinion, required there") String salesNote,
            @Positive @Schema(description = "Back office only: the limit Sales suggest; optional")
            BigDecimal recommendedLimit
    ) {
    }

    /** Sales' opinion on a request the customer filed. */
    @Schema(description = "Sales' opinion")
    public record Recommend(
            @NotNull @PositiveOrZero @Schema(description = "The version read") Long version,
            @NotBlank @Size(max = 1000) String note,
            @Positive @Schema(description = "The limit Sales suggest; optional") BigDecimal recommendedLimit
    ) {
    }

    /** The accountant's approval: at most the limit asked. */
    @Schema(description = "Approve a credit request")
    public record Approve(
            @NotNull @PositiveOrZero @Schema(description = "The version of the request read") Long version,
            @NotNull @Positive @Schema(description = "The limit granted: above zero, at most the limit asked")
            BigDecimal approvedLimit,
            @NotNull @Min(0) @Max(365) Integer approvedTermDays,
            @Size(max = 1000) @Schema(description = "Kept as the note of the customer's credit; optional") String note,
            @Schema(description = "review.creditTermsVersion as read: the version of the customer's credit; -1 or "
                    + "absent while they have none. A stale one is 409 OPTIMISTIC_LOCK") Long creditTermsVersion
    ) {
    }

    /** The accountant's refusal, with the reason. */
    @Schema(description = "Reject a credit request")
    public record Reject(
            @NotNull @PositiveOrZero Long version,
            @NotBlank @Size(max = 1000) String reason
    ) {
    }

    /** Take back one's own open request. */
    @Schema(description = "Withdraw a credit request")
    public record Withdraw(@NotNull @PositiveOrZero Long version) {
    }
}
