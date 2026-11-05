package com.stockflow.inventory.internal.controller.dto;

import java.math.BigDecimal;

/**
 * A bin worth putting a task's goods into. {@code freeUnits} and {@code freeWeightKg} are null when the warehouse
 * map sets no limit for the bin.
 *
 * @param reason {@code SAME_LOT}, {@code SAME_SKU}, {@code EMPTY} or {@code HAS_ROOM}, best first
 */
public record PutawaySuggestionResponse(String locationCode, String reason, Integer freeUnits,
                                        BigDecimal freeWeightKg) {
}
