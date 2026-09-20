package com.stockflow.identity.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * What the API returns for one role.
 *
 * <p>A separate type from {@code identity.api.RoleSummary}, on purpose — same reasoning as
 * {@code inventory.internal.controller.StockItemResponse} vs. {@code StockAvailability}: the api
 * type is the cross-module Java contract, this is the HTTP wire format, and tying them together
 * means a Swagger/JSON-shape change for this screen becomes a change to what other modules compile
 * against.</p>
 */
@Schema(name = "Role", description = "One of the platform's roles")
public record RoleResponse(
        @Schema(example = "ACCOUNTANT") String code,
        String name,
        String description,
        Instant createdAt,
        String createdBy,
        Instant lastModifiedAt,
        String lastModifiedBy
) {
}
