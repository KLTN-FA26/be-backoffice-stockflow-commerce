package com.stockflow.inventory.internal.controller.dto;

import java.math.BigDecimal;

/**
 * A bin worth putting a task's carton into: the carton fits it, and its free volume and load take it.
 *
 * @param reason       {@code SAME_LOT}, {@code EMPTY}, {@code SAME_SKU} or {@code OTHER_SKU}: the group, best first
 *                     (05 §4.2, 2026-11-08)
 * @param freeVolumeM3 the bin's computed volume less the cartons in it (kltn-docs 06 BR-19); null for a bin on a
 *                     level without a clear height, which is not limited
 * @param freeWeightKg null when the warehouse map sets no weight limit for the bin
 */
public record PutawaySuggestionResponse(String locationCode, String reason, BigDecimal freeVolumeM3,
                                        BigDecimal freeWeightKg) {
}
