package com.stockflow.production.internal.controller.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record SampleRequestRowResponse(UUID id, String requestNumber, UUID customerId, int round, String status,
                                       UUID designSnapshotId, String blankSku, int quantity, LocalDate dueDate,
                                       Instant sentAt, Instant respondedAt,
                                       SampleRequestResponse.ProductionOrderRef productionOrder, Instant createdAt) {
}
