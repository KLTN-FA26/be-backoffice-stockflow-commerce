package com.stockflow.inventory.internal.service;

import com.stockflow.inventory.api.InventoryPolicy;
import com.stockflow.inventory.api.ItemLogistics;
import com.stockflow.inventory.api.StockThresholdEvaluation;

import java.util.UUID;

/**
 * An inventory item addressed by its own id. The item is what stock, thresholds and picking hang off, so it is read
 * and edited without going through a product or a variant: nothing here knows either exists. The version an edit
 * quotes back is the item's, and every method answers with the same {@link Detail} so a client never has to read
 * twice to see what it changed.
 */
public interface InventoryItems {

    /** The item's three groups: identity and logistics, stock policy, and what the policy says about stock now. */
    record Detail(UUID id, String sku, long version, ItemLogistics logistics, InventoryPolicy policy,
                  StockThresholdEvaluation evaluation) {}

    /** @throws com.stockflow.common.error.BusinessException INVENTORY_ITEM_NOT_FOUND when no item has this id */
    Detail get(UUID id);

    /** Replaces the item's logistics when {@code expectedVersion} is still current. */
    Detail describe(UUID id, long expectedVersion, ItemLogistics logistics);

    /** Replaces the item's stock policy when {@code expectedVersion} is still current. */
    Detail configure(UUID id, long expectedVersion, InventoryPolicy policy);
}
