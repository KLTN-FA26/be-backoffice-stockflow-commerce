package com.stockflow.warehouse.internal.service;

import java.util.UUID;

/** @param color {@code #RRGGBB} in either case, or {@code null} */
public record CreateZoneCommand(UUID warehouseId, String name, String color) {
}
