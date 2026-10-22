package com.stockflow.inventory.api;

import java.util.Objects;
import java.util.UUID;

/**
 * Intent: the order was released to a warehouse; turn its SKU reservations into allocations on pickable bins of
 * that warehouse (docs 04c F-FUL step 02, BR-STK-006). One order is fulfilled from one warehouse (OQ-04).
 *
 * @param warehouseCode the warehouse's prefix ({@code HCM}), the first part of every location code in it
 */
public record AllocateStockCommand(UUID orderId, String warehouseCode) {

    public AllocateStockCommand {
        Objects.requireNonNull(orderId, "orderId");
        if (warehouseCode == null || warehouseCode.isBlank()) {
            throw new IllegalArgumentException("warehouseCode is required");
        }
    }
}
