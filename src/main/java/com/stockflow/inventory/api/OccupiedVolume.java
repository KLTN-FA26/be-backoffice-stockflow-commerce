package com.stockflow.inventory.api;

import java.math.BigDecimal;
import java.util.Map;

/**
 * What the cartons standing in a warehouse take of it (kltn-docs 06 §4.6 "Đang chiếm", C40): every carton IN_STOCK at any
 * of its locations - bins and areas alike, receiving, quarantine, staging, dispatch and overflow included - whatever
 * the status of the stock in it. A carton is its carton unit's length x width x height (06 BR-19), the measure putaway
 * fits it into a bin by, and weighs that unit's full weight, nothing when it has none (the item has no weight of its
 * own, V20261215000100).
 *
 * @param totalM3            Σ their volume, six decimals
 * @param totalKg            Σ their full weight, for reference (06 §4.6 step 3)
 * @param cartons            how many cartons stand there
 * @param cartonsWithoutSize those whose carton unit lacks a dimension: they take no volume here, so the total is short by
 *                           them - the board says how many
 * @param m3ByLocation       the volume per location code; a location with nothing in it is absent
 */
public record OccupiedVolume(BigDecimal totalM3, BigDecimal totalKg, int cartons, int cartonsWithoutSize,
                             Map<String, BigDecimal> m3ByLocation) {
}
