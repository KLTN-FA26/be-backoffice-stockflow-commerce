package com.stockflow.warehouse.api;

import java.math.BigDecimal;

/**
 * One bin as the location list reads its capacity (#456; kltn-docs 06 §4.6, BR-19): its computed volume, its weight
 * limit and whether it is usable now - the bin, its shelf and its warehouse all ACTIVE, the rule the capacity board
 * counts a bin's volume by ({@link WarehouseCapacity}).
 *
 * @param shelfCode    the code of the shelf it is on
 * @param volumeM3     width x length x its level's clear height, six decimals; null without a clear height (C40)
 * @param maxWeightKg  its weight limit; null for none
 * @param status       its effective status: its own, or its shelf's or warehouse's when they are not ACTIVE
 * @param usable       {@code status} lets it hold cartons now; an unusable bin offers no room
 */
public record BinCapacity(String locationCode, String shelfCode, BigDecimal volumeM3, BigDecimal maxWeightKg,
                          String status, boolean usable) {
}
