package com.stockflow.inventory.api;

import com.stockflow.common.domain.Sku;

public interface InventoryControlService {
    /** Joins the product transaction. Rejects unsafe changes against live stock before applying. */
    InventoryControlItem configure(Sku sku, long expectedVersion, InventoryPolicy policy);

    InventoryControlItem item(Sku sku);

    InventoryPolicy policy(Sku sku);

    StockThresholdEvaluation evaluate(Sku sku);
}
