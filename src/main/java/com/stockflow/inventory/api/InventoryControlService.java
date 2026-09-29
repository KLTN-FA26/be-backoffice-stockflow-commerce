package com.stockflow.inventory.api;

import com.stockflow.common.domain.Sku;
public interface InventoryControlService {
    /** Joins the product transaction. Rejects unsafe changes against live stock before applying. */
    void configure(Sku sku, InventoryPolicy policy);
    InventoryPolicy policy(Sku sku);
    StockThresholdEvaluation evaluate(Sku sku);
}
