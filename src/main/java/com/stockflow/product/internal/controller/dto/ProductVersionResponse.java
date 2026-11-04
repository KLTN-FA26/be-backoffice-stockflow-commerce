package com.stockflow.product.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One entry of a product's history (SCRUM-86/WBS 3.1.8.2, SCRUM-461): what changed, who did it, when,
 * from which status to which, and why. The product's own moves, and what changed on its variants, images,
 * logistics and stock policy.
 *
 * <p>{@code action} and {@code outcome} are what the entries carried when they came from the audit trail,
 * kept for screens that show them: {@code action} is derived from {@code changeType}, and {@code outcome} is
 * always {@code SUCCESS}, because only changes that happened are history.</p>
 */
@Schema(name = "ProductVersion", description = "One entry of a product's history")
public record ProductVersionResponse(

        @Schema(description = "CREATE, UPDATE, TRANSITION, APPROVE or REJECT, derived from changeType", example = "TRANSITION")
        String action,

        @Schema(description = "Who did it - a scheduled job or listener shows as \"system\".")
        String actorName,

        @Schema(example = "SUCCESS")
        String outcome,

        Instant occurredAt,

        @Schema(description = "CREATED, UPDATED, SUBMITTED, APPROVED, REJECTED, SUSPENDED, RESUMED, DISCONTINUED, "
                + "VARIANT_ADDED, VARIANT_UPDATED, VARIANT_STATUS, MEDIA, LOGISTICS, INVENTORY_CONTROL, PURCHASING, UOM_CONVERSION or SALES_UNIT",
                example = "DISCONTINUED")
        String changeType,

        @Schema(description = "PRODUCT, VARIANT, MEDIA, LOGISTICS, INVENTORY_CONTROL, PURCHASING, UOM_CONVERSION or SALES_UNIT", example = "PRODUCT")
        String subjectType,

        @Schema(description = "The variant, image or SKU concerned; empty for the product itself")
        UUID subjectId,

        @Schema(description = "Status of the subject before the change, when it moved; empty on entries recovered "
                + "from the audit trail")
        String fromStatus,

        String toStatus,

        @Schema(description = "Why, when the change needs one (suspending, discontinuing) or gave one")
        String reason,

        @Schema(description = "The fields an edit changed")
        List<String> changedFields
) {
}
