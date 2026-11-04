package com.stockflow.identity.internal.controller.dto;

import com.stockflow.identity.internal.domain.PasswordPolicy;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/** Body of {@code POST /api/v1/identity/users}. */
@Schema(description = "A new staff account")
public record CreateUserRequest(
        @Schema(example = "lan.nguyen") @NotBlank
        @Pattern(regexp = "^[A-Za-z0-9._-]{3,100}$",
                message = "username must be 3-100 letters, digits, dots, dashes or underscores")
        String username,
        @NotBlank @Email @Size(max = 320) String email,
        @Size(max = 200) String fullName,
        @Schema(description = "Leave empty to have one generated and returned once")
        @Size(min = PasswordPolicy.MIN_LENGTH, max = PasswordPolicy.MAX_LENGTH,
                message = "password must be between 10 and 72 characters")
        @Pattern(regexp = PasswordPolicy.PATTERN,
                message = "password must contain upper-case, lower-case and digit characters")
        String password,
        @Schema(example = "[\"WAREHOUSE_STAFF\"]") @NotEmpty @Size(max = 20) List<@NotBlank String> roles,
        @Schema(description = "Warehouses the user works in; needed for WAREHOUSE-scoped roles")
        @Size(max = 50) List<@NotNull UUID> warehouseIds) {
}
