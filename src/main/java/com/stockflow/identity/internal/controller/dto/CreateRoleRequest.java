package com.stockflow.identity.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Body of {@code POST /api/v1/identity/roles}. */
@Schema(description = "A new custom role, empty or starting from another role's permissions")
public record CreateRoleRequest(
        @Schema(example = "SHIFT_LEAD", description = "Upper-case letters, digits and underscores")
        @NotBlank @Pattern(regexp = "^[A-Za-z][A-Za-z0-9_]{1,63}$",
                message = "code must be 2-64 letters, digits or underscores, starting with a letter")
        String code,
        @NotBlank @Size(max = 200) String name,
        @Size(max = 1000) String description,
        @Schema(example = "WAREHOUSE_STAFF", description = "Optional: copy this role's permissions")
        String copyPermissionsFrom) {
}
