package com.stockflow.inventory.internal.controller.dto;

import java.time.Instant;
import java.util.UUID;

public record StockMoveResponse(UUID movementId, String sku, String lotNumber, String fromLocation,
                                String toLocation, int quantity, Instant occurredAt) {
}
