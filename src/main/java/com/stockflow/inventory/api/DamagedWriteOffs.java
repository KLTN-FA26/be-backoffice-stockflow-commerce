package com.stockflow.inventory.api;

import java.util.List;
import java.util.UUID;

/**
 * The cartons of a production order's goods written off as damaged after they were received (kltn-docs 20 §4.8, 17
 * §4.13): what the order module reads to draw up a replacement production order and to check the cartons it names
 * (17 BR-31). Its own interface rather than more of {@link InventoryService}: a read of the adjustments' outcome, with
 * nothing to hold or move.
 */
public interface DamagedWriteOffs {

    /**
     * The posted {@code DAMAGED} write-offs of whole cartons of the lots production order {@code productionOrderId}
     * opened (one lot per SKU, numbered with its code), oldest first. A write-off still pending, rejected or withdrawn
     * is not one: nothing was written off.
     */
    List<DamagedWriteOff> ofProductionOrder(UUID productionOrderId);
}
