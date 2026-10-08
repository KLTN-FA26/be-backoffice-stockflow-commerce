package com.stockflow.inventory.internal.controller.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record TransferOrderResponse(UUID transferId, String number, UUID fromWarehouseId, UUID toWarehouseId,
                                    String status, String reason, LocalDate expectedDate, List<Line> lines,
                                    UUID submittedBy, Instant submittedAt, UUID approvedBy, Instant approvedAt,
                                    UUID dispatchedBy, Instant dispatchedAt, String cancelReason, Instant createdAt,
                                    long version) {

    public record Line(UUID lineId, int lineNo, String sku, String lotNumber, int requested, int shipped) {
    }
}
