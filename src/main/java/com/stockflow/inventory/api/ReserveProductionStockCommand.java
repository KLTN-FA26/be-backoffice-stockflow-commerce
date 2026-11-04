package com.stockflow.inventory.api;

import com.stockflow.common.domain.Sku;

import java.util.UUID;

/**
 * Hold blanks for a production order rather than for an order (decision Q1, 2026-10-12: a sample's
 * blanks are held from the moment the sample request is submitted, so no other order can take them).
 *
 * <p>Drawn only from {@code warehouseCode}: the workshop prints in the warehouse the production order
 * was released to, and a hold in another warehouse would be a promise it cannot walk to. The hold
 * belongs to the production order — never to an invented order id — and does not expire.</p>
 *
 * @param requestId the caller's idempotency key; a retry with the same key returns the original holds
 */
public record ReserveProductionStockCommand(UUID requestId, Sku sku, int quantity, UUID productionOrderId,
                                            String warehouseCode) {

    public ReserveProductionStockCommand {
        if (requestId == null) throw new IllegalArgumentException("requestId is required");
        if (sku == null) throw new IllegalArgumentException("sku is required");
        if (quantity < 1) throw new IllegalArgumentException("quantity must be at least 1");
        if (productionOrderId == null) throw new IllegalArgumentException("productionOrderId is required");
        if (warehouseCode == null || warehouseCode.isBlank()) {
            throw new IllegalArgumentException("warehouseCode is required");
        }
    }
}
