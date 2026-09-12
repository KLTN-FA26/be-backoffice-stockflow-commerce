package com.stockflow.identity.internal.controller.dto;

import jakarta.validation.constraints.NotBlank;

/** Body of {@code POST /api/v1/identity/users/{userId}/roles}. */
public record AssignRoleRequest(@NotBlank String roleCode) {
}
