package com.stockflow.identity.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** Body of {@code PATCH /api/v1/identity/users/{userId}}. Omitted fields are left as they are. */
@Schema(description = "Edit a staff account's profile; the username never changes")
public record UpdateUserRequest(
        @Email @Size(max = 320) String email,
        @Size(max = 200) String fullName,
        @Schema(description = "The version the editor loaded") @NotNull @PositiveOrZero Long version) {
}
