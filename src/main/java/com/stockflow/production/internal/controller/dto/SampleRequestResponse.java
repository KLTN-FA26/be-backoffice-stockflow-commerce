package com.stockflow.production.internal.controller.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record SampleRequestResponse(UUID id, String requestNumber, UUID customerId, UUID quoteId, UUID previousRoundId,
                                    UUID nextRoundId, int round, String status, Specification specification,
                                    Instant sentAt, Instant respondedAt, UUID respondedBy, String customerFeedback,
                                    String cancellationReason, ProductionOrderRef productionOrder, List<Round> rounds,
                                    Instant createdAt, String createdBy, Instant lastModifiedAt,
                                    String lastModifiedBy, long version) {

    public record Specification(UUID designSnapshotId, String blankSku, int quantity, LocalDate dueDate) {
    }

    public record ProductionOrderRef(UUID id, String orderNumber, String status, UUID warehouseId) {
    }

    public record Round(UUID id, String requestNumber, int round, String status, UUID designSnapshotId,
                        String blankSku, int quantity, LocalDate dueDate, Instant sentAt, Instant respondedAt,
                        UUID respondedBy, String customerFeedback, String cancellationReason) {
    }
}
