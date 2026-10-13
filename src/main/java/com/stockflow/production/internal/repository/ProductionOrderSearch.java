package com.stockflow.production.internal.repository;

import com.stockflow.production.internal.domain.ProductionOrder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Read side of production orders: the shop list (SCRUM-406 filters by type, status and due date), one
 * order with its status history, and the order of each sample round. Every read is limited to the
 * warehouses of a warehouse-bound caller.
 */
public interface ProductionOrderSearch {

    record Criteria(List<ProductionOrder.Type> types, List<ProductionOrder.Status> statuses, UUID warehouseId,
                    LocalDate dueFrom, LocalDate dueTo, UUID sampleRequestId, UUID salesOrderId, String number) {
    }

    record Row(UUID id, String orderNumber, ProductionOrder.Type type, String mode, ProductionOrder.Status status,
               String holdReason, UUID warehouseId, String blankSku, UUID designSnapshotId, int plannedQuantity,
               int goodQuantity, LocalDate dueDate, UUID sampleRequestId, UUID salesOrderId, UUID salesOrderLineId,
               UUID parentId, Instant createdAt) {
    }

    record HistoryEntry(String fromStatus, String toStatus, String reason, Instant changedAt, UUID changedBy) {
    }

    record Detail(Row row, UUID approvedSampleId, String designChecksum, int issuedQuantity, int printedQuantity,
                  int scrapQuantity, Instant prepressCheckedAt, Instant completedAt, Instant cancelledAt,
                  String cancellationReason, String createdBy, Instant lastModifiedAt, long version,
                  List<HistoryEntry> history, String origin, Subcontract subcontract) {
    }

    /** The terms of a subcontracted order; null on one printed in house. */
    record Subcontract(String method, UUID subcontractorId, java.math.BigDecimal unitPrice, String currency,
                       UUID purchaseOrderId) {
    }

    Page<Row> search(Criteria criteria, Pageable pageable);

    Optional<Detail> detail(UUID id);

    /** The SAMPLE production order of each of these rounds that has one, keyed by sample request id. */
    Map<UUID, Row> forSamples(Collection<UUID> sampleRequestIds);
}
