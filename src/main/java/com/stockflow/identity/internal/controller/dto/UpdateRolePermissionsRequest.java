package com.stockflow.identity.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Body of {@code PUT /api/v1/identity/roles/{roleCode}/permissions}. */
@Schema(description = "The complete set of permissions the role should grant")
public record UpdateRolePermissionsRequest(
        @Schema(description = "The matrix version the editor loaded, from GET .../permissions",
                example = "7")
        @NotNull @PositiveOrZero Long version,
        @Schema(description = "Every permission the role grants after the save, as resource:ACTION",
                example = "[\"procurement-purchase-orders:READ\", \"procurement-purchase-orders:APPROVE\"]")
        @NotNull @Size(max = 2000) List<@NotBlank String> permissions) {
}
