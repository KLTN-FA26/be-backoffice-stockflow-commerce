package com.stockflow.product.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * One entry of a product's audit trail (SCRUM-86/WBS 3.1.8.2) — what changed, who did it, when,
 * and whether it succeeded. Not a field-level diff: see {@code AuditEntry}'s own javadoc for why
 * this codebase deliberately does not capture one generically.
 */
@Schema(name = "ProductVersion", description = "One entry of a product's audit trail")
public record ProductVersionResponse(

        @Schema(example = "UPDATE")
        String action,

        @Schema(description = "Who did it - a scheduled job or listener shows as \"system\".")
        String actorName,

        @Schema(example = "SUCCESS")
        String outcome,

        Instant occurredAt
) {
}
