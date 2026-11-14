package com.stockflow.warehouse.internal.service;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Everything about a warehouse that may change. No prefix and no map unit: both are fixed at
 * registration (BR-13).
 *
 * @param expectedVersion   the version the edit was based on; a different current version is a 409
 * @param utilizationFactor the capacity board's factor (kltn-docs 06 §4.6, C43), (0, 1]; null keeps the current one -
 *                          a client that predates the board edits the rest without resetting it
 */
public record UpdateWarehouseCommand(UUID warehouseId, String name, String address, String returnAddress,
                                     BigDecimal mapWidth, BigDecimal mapHeight, long expectedVersion,
                                     BigDecimal utilizationFactor) {
}
