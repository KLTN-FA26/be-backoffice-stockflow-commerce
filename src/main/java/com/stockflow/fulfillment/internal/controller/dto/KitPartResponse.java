package com.stockflow.fulfillment.internal.controller.dto;

import java.util.UUID;

/**
 * The kit ("bộ", SCRUM-287 LP2-6) a package is a part of; absent ({@code null}) for an ordinary package. The label prints
 * it as "Bộ {@code partNo}/{@code partCount}"; load planning keeps the parts of one {@code groupId} together.
 *
 * @param partNo    1..{@code partCount}; part 1 holds the unit, the others no goods
 * @param partCount the item's {@code package_count} when the kit was opened
 * @param orderLineId the line whose one unit the kit holds (in part 1)
 */
public record KitPartResponse(UUID groupId, int partNo, int partCount, UUID orderLineId) {
}
