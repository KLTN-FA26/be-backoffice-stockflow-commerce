package com.stockflow.inventory.api;

import com.stockflow.common.domain.Sku;

import java.util.UUID;

/**
 * Intent (4B-7, kltn-docs 17 §4.2 step 3, BR-21; 20 §5.6): hold whole cartons of a SKU for an order line at the order's
 * shipping warehouse - up to {@code cartons}, as many as are available to promise there.
 *
 * @param requestId     de-duplication key: the same request holds once
 * @param warehouseCode the order's shipping warehouse (20 BR-17)
 * @param cartons       the cartons still to hold for the line
 * @param unitsPerCarton the SKU's standard count per carton: a carton is held whole or not at all (BR-21)
 */
public record HoldStockCommand(UUID requestId, UUID orderId, Sku sku, String warehouseCode, int cartons,
                               int unitsPerCarton) {

    public HoldStockCommand {
        if (requestId == null) throw new IllegalArgumentException("requestId is required");
        if (orderId == null) throw new IllegalArgumentException("orderId is required");
        if (sku == null) throw new IllegalArgumentException("sku is required");
        if (warehouseCode == null || warehouseCode.isBlank()) throw new IllegalArgumentException("warehouseCode is required");
        if (cartons < 1) throw new IllegalArgumentException("cartons must be at least 1");
        if (unitsPerCarton < 1) throw new IllegalArgumentException("unitsPerCarton must be at least 1");
    }
}
