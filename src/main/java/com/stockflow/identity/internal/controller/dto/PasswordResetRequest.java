package com.stockflow.identity.internal.controller.dto;

import com.stockflow.identity.internal.domain.PasswordPolicy;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Body of {@code POST /api/v1/identity/users/{userId}/password-reset}; may be empty. */
@Schema(description = "Set a new temporary password; leave it empty to have one generated")
public record PasswordResetRequest(
        @Size(min = PasswordPolicy.MIN_LENGTH, max = PasswordPolicy.MAX_LENGTH,
                message = "newPassword must be between 10 and 72 characters")
        @Pattern(regexp = PasswordPolicy.PATTERN,
                message = "newPassword must contain upper-case, lower-case and digit characters")
        String newPassword) {
}
