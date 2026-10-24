package com.stockflow.fulfillment.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

/**
 * What a damage report at picking did (SCRUM-277).
 *
 * @param damageLocationCode the quarantine area the picker takes the damaged units to
 * @param orderOnHold        true when no other stock replaced the damaged units: the SHORT line among {@code lines}
 *                           waits for the coordinator's reallocation and the order is ON_HOLD (INVENTORY)
 * @param line               the line reported, now CANCELLED with {@code damagedQty}
 * @param lines              what replaced it, in walking order: the good units at the same bin, then any replacement
 *                           elsewhere (OPEN, pick them) or the SHORT line
 */
@Schema(name = "PickDamage")
public record PickDamageResponse(UUID damageReportId, String reportNumber, String damageLocationCode,
                                 int damagedQuantity, boolean orderOnHold, PickLineResponse line,
                                 List<PickLineResponse> lines) {
}
