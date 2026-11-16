package com.stockflow.warehouse.internal.service;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Everything about a warehouse that may change. No prefix and no map unit: both are fixed at
 * registration (BR-13).
 *
 * @param utilizationFactor the share of the bins' volume cartons really fill, in (0, 1] (kltn-docs 06 §4.6); null
 *                          keeps the current one, so a client that does not know the field changes nothing
 * @param expectedVersion   the version the edit was based on; a different current version is a 409
 */
public record UpdateWarehouseCommand(UUID warehouseId, String name, String address, String returnAddress,
                                     BigDecimal mapWidth, BigDecimal mapHeight, BigDecimal utilizationFactor,
                                     long expectedVersion) {
}
