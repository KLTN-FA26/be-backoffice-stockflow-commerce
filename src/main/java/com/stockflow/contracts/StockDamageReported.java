package com.stockflow.contracts;

import java.util.UUID;

/**
 * Published by the <b>inventory</b> module when units are found damaged (docs 03 §5 AVAILABLE → DAMAGED,
 * BR-STK-003): they left available-to-promise and wait, DAMAGED, in a quarantine area for a warehouse manager's
 * decision. One event per damage report. See {@code package-info.java} for the rules on changing this contract.
 *
 * @param origin                {@code STOCK_CHECK} (found on a shelf) or {@code PICKING} (found by a picker)
 * @param reasonCode            {@code BROKEN}, {@code DEFORMED}, {@code WET_OR_MOULDY}, {@code DIRTY_OR_MISPRINTED}
 *                              or {@code OTHER}
 * @param orderId               the order whose allocation the units were picked against; null for a stock check
 * @param orderOnHold           true when no other stock could replace the order's damaged units: its pick line is
 *                              short and the order ON_HOLD (INVENTORY) until the coordinator reallocates it
 * @param skuReservationShortfall units the SKU's live checkout reservations now promise beyond its free sellable
 *                              stock (0 when they are still covered): those orders will not allocate in full at
 *                              release unless stock arrives first
 */
public record StockDamageReported(
        UUID reportId,
        String reportNumber,
        String origin,
        UUID warehouseId,
        String sku,
        String lotNumber,
        String sourceLocationCode,
        String damageLocationCode,
        int quantity,
        String reasonCode,
        UUID orderId,
        boolean orderOnHold,
        int skuReservationShortfall
) {
}
