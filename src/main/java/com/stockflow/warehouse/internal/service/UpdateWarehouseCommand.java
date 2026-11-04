package com.stockflow.warehouse.internal.service;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Everything about a warehouse that may change. No prefix and no map unit: both are fixed at
 * registration (BR-13).
 *
 * @param expectedVersion the version the edit was based on; a different current version is a 409
 */
public record UpdateWarehouseCommand(UUID warehouseId, String name, String address, String returnAddress,
                                     BigDecimal mapWidth, BigDecimal mapHeight, long expectedVersion) {
}
