package com.stockflow.order.internal.controller.dto;

import com.stockflow.order.internal.domain.WindowChangeReason;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.UUID;

/**
 * 4B-8 (kltn-docs 17 BR-28): a delivery batch's new window, why it moves, and the customer's consent - exactly one of
 * an uploaded file and a chat message.
 */
public record WindowChangeRequest(
        @NotNull(message = "windowStart is required") LocalDate windowStart,
        @NotNull(message = "windowEnd is required") LocalDate windowEnd,
        @NotNull(message = "reasonCode is required")
        @Schema(description = "CUSTOMER_REQUEST (the customer asked, or takes the goods early) or SELLER_DELAY")
        WindowChangeReason reasonCode,
        @NotBlank(message = "note is required") @Size(max = 1000, message = "note is at most 1000 characters")
        String note,
        @Schema(description = "The upload of the consent file, from .../window-change/evidence/uploads")
        UUID evidenceUploadId,
        @Schema(description = "The chat message in which the customer agreed")
        UUID evidenceMessageId) {
}
