package com.stockflow.fulfillment.internal.controller.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * "Đóng bộ" (SCRUM-287 LP2-6): open the parts of a kit for one unit of an order line whose item ships as several boxes.
 *
 * @param orderLineId the line the unit is of
 * @param partCount   optional: how many parts the station expects (the line's {@code packageCount}); when sent it must
 *                    be the item's {@code package_count}, or 422 KIT_PART_COUNT_MISMATCH
 * @param packageType CARTON (default), BAG, PALLET, CRATE or OTHER, for every part
 */
public record OpenKitRequest(@NotNull UUID orderLineId,
                             @Min(2) @Max(99) Integer partCount,
                             @Size(max = 16) String packageType) {
}
