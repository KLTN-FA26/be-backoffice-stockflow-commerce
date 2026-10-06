package com.stockflow.identity.internal.controller.dto;

import com.stockflow.identity.internal.domain.PasswordPolicy;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "Change the caller's password")
public record ChangePasswordRequest(
        @NotBlank(message = "currentPassword is required")
        String currentPassword,
        @NotBlank(message = "newPassword is required")
        @Size(min = PasswordPolicy.MIN_LENGTH, max = PasswordPolicy.MAX_LENGTH,
                message = "newPassword must be between 10 and 72 characters")
        @Pattern(regexp = PasswordPolicy.PATTERN,
                message = "newPassword must contain upper-case, lower-case and digit characters")
        String newPassword,
        @Schema(description = "End every other signed-in device together with the change",
                defaultValue = "false")
        boolean logoutOtherDevices
) {
}
