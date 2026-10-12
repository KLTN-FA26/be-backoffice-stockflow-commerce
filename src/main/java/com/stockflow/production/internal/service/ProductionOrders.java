package com.stockflow.production.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.production.internal.domain.ProductionOrder;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Reading production orders (SCRUM-395; the list SCRUM-406 shows). Creating them is not a use case a
 * person calls: a SAMPLE order is born of a submitted sample round, an ORDER order of a release. A
 * warehouse-bound caller sees only the orders of their warehouses.
 */
public interface ProductionOrders {

    record Criteria(List<ProductionOrder.Type> types, List<ProductionOrder.Status> statuses, UUID warehouseId,
                    LocalDate dueFrom, LocalDate dueTo, UUID sampleRequestId, UUID salesOrderId, String number,
                    int page, int size, String sort) {
    }

    record Row(UUID id, String orderNumber, ProductionOrder.Type type, String mode, ProductionOrder.Status status,
               String holdReason, UUID warehouseId, String blankSku, UUID designSnapshotId, int plannedQuantity,
               int goodQuantity, LocalDate dueDate, UUID sampleRequestId, UUID salesOrderId, UUID salesOrderLineId,
               UUID parentId, Instant createdAt) {
    }

    record StatusChange(String fromStatus, String toStatus, String reason, Instant changedAt, UUID changedBy) {
    }

    record Detail(Row summary, UUID approvedSampleId, String designChecksum, int issuedQuantity, int printedQuantity,
                  int scrapQuantity, Instant prepressCheckedAt, Instant completedAt, Instant cancelledAt,
                  String cancellationReason, String createdBy, Instant lastModifiedAt, long version,
                  List<StatusChange> history) {
    }

    PageResponse<Row> list(CurrentUser user, Criteria criteria);

    Detail get(CurrentUser user, UUID productionOrderId);
}
