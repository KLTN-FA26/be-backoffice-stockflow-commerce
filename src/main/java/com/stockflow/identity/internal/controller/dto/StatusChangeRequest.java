package com.stockflow.identity.internal.controller.dto;

import com.stockflow.identity.internal.domain.StatusChangeReason;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of lock, unlock, disable and enable ({@code POST /api/v1/identity/users/{userId}/{action}}). */
@Schema(description = "Why the account's status is being changed")
public record StatusChangeRequest(
        @Schema(description = "Free text, 5 to 500 characters; kept in the account's status history and the audit trail",
                minLength = StatusChangeReason.MIN_LENGTH, maxLength = StatusChangeReason.MAX_LENGTH)
        @NotBlank(message = "reason is required")
        @Size(min = StatusChangeReason.MIN_LENGTH, max = StatusChangeReason.MAX_LENGTH,
                message = "reason must be between 5 and 500 characters")
        String reason) {
}
