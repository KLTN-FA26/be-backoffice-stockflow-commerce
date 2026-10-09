package com.stockflow.inventory.internal.controller.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record TransferOrderRowResponse(UUID transferId, String number, UUID fromWarehouseId, UUID toWarehouseId,
                                       String status, LocalDate expectedDate, Instant createdAt, Instant dispatchedAt) {
}
