package com.stockflow.product.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One entry of a product's history (SCRUM-461, WBS 3.1.8.2): what changed, who did it, when, from which
 * status to which, and why.
 *
 * @param changeType    {@code CREATED, UPDATED, SUBMITTED, APPROVED, REJECTED, SUSPENDED, RESUMED, DISCONTINUED}
 *                      for the product; {@code VARIANT_ADDED, VARIANT_UPDATED, VARIANT_STATUS},
 *                      {@code MEDIA}, {@code LOGISTICS}, {@code INVENTORY_CONTROL} for what hangs off it
 * @param subjectType   {@code PRODUCT, VARIANT, MEDIA, LOGISTICS, INVENTORY_CONTROL, PURCHASING, UOM_CONVERSION, SALES_UNIT}
 * @param subjectId     the variant, image or SKU concerned; null for the product itself, and for entries
 *                      recovered from the audit trail that named no single image
 * @param fromStatus    status of the subject before; null when the change moved none, and for entries
 *                      recovered from the audit trail, which never recorded it
 * @param toStatus      status of the subject after
 * @param reason        why, when the change needs one (suspending, discontinuing) or gave one
 * @param changedFields the fields an edit changed
 * @param actorId       the user, or null when the system acted
 * @param actorName     the name to show for {@code actorId}; {@code "system"} when there is none
 */
public record ProductChange(
        String changeType,
        String subjectType,
        UUID subjectId,
        String fromStatus,
        String toStatus,
        String reason,
        List<String> changedFields,
        UUID actorId,
        String actorName,
        Instant occurredAt
) {
    public ProductChange {
        changedFields = changedFields == null ? List.of() : List.copyOf(changedFields);
    }
}
