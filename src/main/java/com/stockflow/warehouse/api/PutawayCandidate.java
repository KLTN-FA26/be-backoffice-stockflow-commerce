package com.stockflow.warehouse.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A bin a putaway may choose, with the limits the warehouse map holds for it.
 *
 * @param capacityUnits the bin's capacity in base units; {@code null} when the map sets none
 * @param maxWeightKg   the heaviest load the bin takes; {@code null} when the map sets none
 */
public record PutawayCandidate(UUID locationId, String locationCode, Integer capacityUnits, BigDecimal maxWeightKg) {
}
