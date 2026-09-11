package com.stockflow.inventory.api;

import com.stockflow.common.domain.Sku;

import java.util.UUID;

/**
 * Intent: hold stock for one order line.
 *
 * @param requestId de-duplication key. The caller resending the same requestId — a flaky network,
 *                  a double-clicked button — must reserve exactly once.
 */
public record ReserveStockCommand(UUID requestId, Sku sku, int quantity, UUID orderId) {

    public ReserveStockCommand {
        if (requestId == null) throw new IllegalArgumentException("requestId is required");
        if (sku == null) throw new IllegalArgumentException("sku is required");
        if (quantity < 1) throw new IllegalArgumentException("quantity must be at least 1");
        if (orderId == null) throw new IllegalArgumentException("orderId is required");
    }
}
