package com.stockflow.order.internal.controller.dto;

import com.stockflow.common.validation.ValidSku;
import com.stockflow.order.api.PaymentTerm;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Request bodies of the quote endpoints (SCRUM-448). Every change carries the version the caller read. */
public final class QuoteRequests {

    private QuoteRequests() {
    }

    @Schema(description = "A price break: from minQuantity units up, unitPrice VND per unit (whole dong)")
    public record Tier(
            @Min(value = 1, message = "minQuantity must be at least 1") int minQuantity,
            @NotNull(message = "unitPrice is required") @DecimalMin(value = "1", message = "unitPrice must be positive")
            @Digits(integer = 15, fraction = 0, message = "unitPrice is whole VND") BigDecimal unitPrice) {
    }

    @Schema(description = "A printed blank: the blank SKU, the customer's confirmed design, how it is printed")
    public record Line(
            @Schema(example = "CUP-PP-500") @NotBlank(message = "blankSku is required") @ValidSku String blankSku,
            @NotNull(message = "designSnapshotId is required") UUID designSnapshotId,
            @Size(max = 2000, message = "printSpec is at most 2000 characters") String printSpec,
            @Min(value = 1, message = "quantity must be at least 1") int quantity,
            @Schema(description = "Omit to start from the customer's price list for the blank (SCRUM-428)")
            @Size(max = 20, message = "at most 20 tiers")
            List<@Valid Tier> tiers) {
    }

    public record Create(
            @NotNull(message = "requestId is required") UUID requestId,
            @NotNull(message = "customerId is required") UUID customerId,
            @NotNull(message = "paymentTerm is required") PaymentTerm paymentTerm,
            @Schema(description = "DEPOSIT only; empty takes the customer's default") BigDecimal depositPercent,
            @NotNull(message = "validUntil is required") Instant validUntil,
            @Size(max = 2000, message = "terms are at most 2000 characters") String terms,
            @NotEmpty(message = "a quote needs at least one line") @Size(max = 50, message = "at most 50 lines")
            List<@Valid Line> lines) {
    }

    public record Edit(
            @NotNull(message = "version is required") Long version,
            @NotNull(message = "paymentTerm is required") PaymentTerm paymentTerm,
            BigDecimal depositPercent,
            @NotNull(message = "validUntil is required") Instant validUntil,
            @Size(max = 2000, message = "terms are at most 2000 characters") String terms,
            @NotEmpty(message = "a quote needs at least one line") @Size(max = 50, message = "at most 50 lines")
            List<@Valid Line> lines) {
    }

    /** Reorder of an order placed from a quote; {@code requestId} makes a retried click return the same draft. */
    public record Reorder(@NotNull(message = "requestId is required") UUID requestId) {
    }

    public record Version(@NotNull(message = "version is required") Long version) {
    }

    public record AcceptedLine(@NotNull(message = "lineId is required") UUID lineId,
                               @Min(value = 1, message = "quantity must be at least 1") int quantity) {
    }

    @Schema(description = "Accept at the proposed quantities, or name another quantity (at or above the MOQ) per line")
    public record Acceptance(@NotNull(message = "version is required") Long version,
                             @Size(max = 50, message = "at most 50 lines") List<@Valid AcceptedLine> lines) {
    }

    public record Feedback(@NotNull(message = "version is required") Long version,
                           @NotBlank(message = "feedback is required")
                           @Size(max = 2000, message = "feedback is at most 2000 characters") String feedback) {
    }

    public record Cancellation(@NotNull(message = "version is required") Long version,
                               @NotBlank(message = "reason is required")
                               @Size(max = 500, message = "reason is at most 500 characters") String reason) {
    }

    @Schema(description = "Addresses of the customer for the order; empty takes their default addresses")
    public record Conversion(@NotNull(message = "version is required") Long version,
                             UUID shippingAddressId, UUID billingAddressId) {
    }
}
