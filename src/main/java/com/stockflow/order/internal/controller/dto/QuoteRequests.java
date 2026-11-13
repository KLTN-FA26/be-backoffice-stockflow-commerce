package com.stockflow.order.internal.controller.dto;

import com.stockflow.common.validation.ValidSku;
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
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Request bodies of the quote endpoints (SCRUM-448). Every change carries the version the caller read. */
public final class QuoteRequests {

    private QuoteRequests() {
    }

    @Schema(description = "A price break: from minQuantity cartons up, unitPrice VND per piece (whole dong)")
    public record Tier(
            @Min(value = 1, message = "minQuantity must be at least 1") int minQuantity,
            @NotNull(message = "unitPrice is required") @DecimalMin(value = "1", message = "unitPrice must be positive")
            @Digits(integer = 15, fraction = 0, message = "unitPrice is whole VND") BigDecimal unitPrice) {
    }

    @Schema(description = "Cartons of one of the customer's own SKUs (kltn-docs 14 BR-04, BR-08)")
    public record Line(
            @Schema(example = "LY12OZ-0312345678-01") @NotBlank(message = "sku is required") @ValidSku String sku,
            @Min(value = 1, message = "cartons must be at least 1") int cartons,
            @Schema(description = "Omit to start from the customer's own price for the SKU (13 BR-05)")
            @Size(max = 20, message = "at most 20 tiers")
            List<@Valid Tier> tiers) {
    }

    @Schema(description = "4B-3: a fee line - PLATE_FEE for a SKU on the quote produced for the first time, or "
            + "SHIPPING_FEE; whole VND, a plate fee may be left without its amount for now")
    public record Fee(
            @NotNull(message = "kind is required") com.stockflow.order.api.FeeKind kind,
            @Schema(description = "PLATE_FEE: the SKU it is for; empty for SHIPPING_FEE") @ValidSku String sku,
            @DecimalMin(value = "0", message = "amount is not negative")
            @Digits(integer = 15, fraction = 0, message = "amount is whole VND") BigDecimal amount) {
    }

    public record Create(
            @NotNull(message = "requestId is required") UUID requestId,
            @NotNull(message = "customerId is required") UUID customerId,
            @NotNull(message = "validUntil is required") Instant validUntil,
            @Size(max = 2000, message = "terms are at most 2000 characters") String terms,
            @Schema(description = "SCRUM-502: the date the customer asks to receive the goods, not in the past; "
                    + "optional. It is copied onto the print lines of the order this quote becomes")
            LocalDate requestedDeliveryDate,
            @NotEmpty(message = "a quote needs at least one line") @Size(max = 50, message = "at most 50 lines")
            List<@Valid Line> lines,
            @Schema(description = "4B-3: fee lines. A plate fee (PLATE_FEE, its sku, amount) prices the one the system adds "
                    + "for a SKU produced for the first time; one SHIPPING_FEE with its amount, if the customer pays it")
            @Size(max = 51, message = "at most 51 fee lines")
            List<@Valid Fee> fees) {
    }

    public record Edit(
            @NotNull(message = "version is required") Long version,
            @NotNull(message = "validUntil is required") Instant validUntil,
            @Size(max = 2000, message = "terms are at most 2000 characters") String terms,
            @Schema(description = "SCRUM-502: the date the customer asks to receive the goods, not in the past; "
                    + "optional. It is copied onto the print lines of the order this quote becomes")
            LocalDate requestedDeliveryDate,
            @NotEmpty(message = "a quote needs at least one line") @Size(max = 50, message = "at most 50 lines")
            List<@Valid Line> lines,
            @Schema(description = "4B-3: fee lines. A plate fee (PLATE_FEE, its sku, amount) prices the one the system adds "
                    + "for a SKU produced for the first time; one SHIPPING_FEE with its amount, if the customer pays it")
            @Size(max = 51, message = "at most 51 fee lines")
            List<@Valid Fee> fees) {
    }

    public record Version(@NotNull(message = "version is required") Long version) {
    }

    public record AcceptedLine(@NotNull(message = "lineId is required") UUID lineId,
                               @Min(value = 1, message = "cartons must be at least 1") int cartons) {
    }

    @Schema(description = "Accept at the proposed cartons, or name other cartons (at or above the MOQ) per line")
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
