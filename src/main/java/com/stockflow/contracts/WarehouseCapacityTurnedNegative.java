package com.stockflow.contracts;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Published by the <b>order</b> module when approving a production order - which sends it - turns its receiving
 * warehouse's projected free room (kltn-docs 06 §4.6 "Còn trống dự kiến") from zero or more to below zero (06 §4.6 step 8,
 * BR-21; 17 §4.3 step 7; open question C42, decided 2026-11-05): once, at that crossing, never again for a warehouse
 * already short. The <b>notification</b> module tells the warehouse manager(s) of that warehouse, WARN (00 §6).
 *
 * <p>Facts only: ids, the production order's code and the shortfall. Consumers receive it in-process through
 * {@code @ApplicationModuleListener}; see {@code package-info.java} for the rules on changing this contract.</p>
 *
 * @param shortfallM3 how far below zero the projected free room went, in m³ (three decimals)
 */
public record WarehouseCapacityTurnedNegative(UUID eventId, UUID warehouseId, UUID productionOrderId,
                                              String productionOrderCode, BigDecimal shortfallM3, Instant at) {
}
