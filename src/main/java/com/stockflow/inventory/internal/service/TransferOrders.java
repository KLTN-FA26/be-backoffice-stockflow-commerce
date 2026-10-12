package com.stockflow.inventory.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.inventory.internal.domain.TransferReason;
import com.stockflow.inventory.internal.domain.TransferStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Inter-warehouse transfer orders (SCRUM-326/327): create with an availability check at the source,
 * edit while DRAFT (SCRUM-462), submit (self-approved at or under the threshold), approve or reject, pick, dispatch, cancel.
 * Receiving at the destination is SCRUM-328.
 */
public interface TransferOrders {

    record NewLine(String sku, String lotNumber, int quantity) {
    }

    record Create(UUID fromWarehouseId, UUID toWarehouseId, TransferReason reason, LocalDate expectedDate,
                  List<NewLine> lines) {
    }

    /** The whole DRAFT as the planner now wants it; {@code version} is the one they read. */
    record Revise(long version, UUID fromWarehouseId, UUID toWarehouseId, TransferReason reason,
                  LocalDate expectedDate, List<NewLine> lines) {
    }

    record Line(UUID lineId, int lineNo, String sku, String lotNumber, int requested, int shipped) {
    }

    record Transfer(UUID transferId, String number, UUID fromWarehouseId, UUID toWarehouseId, TransferStatus status,
                    TransferReason reason, LocalDate expectedDate, List<Line> lines, UUID submittedBy,
                    Instant submittedAt, UUID approvedBy, Instant approvedAt, UUID dispatchedBy, Instant dispatchedAt,
                    String cancelReason, Instant createdAt, long version) {
    }

    record Row(UUID transferId, String number, UUID fromWarehouseId, UUID toWarehouseId, TransferStatus status,
               LocalDate expectedDate, Instant createdAt, Instant dispatchedAt) {
    }

    Transfer create(Create command);

    Transfer get(UUID transferId);

    /**
     * Changes a DRAFT (new, or rejected back to DRAFT) and checks it as {@link #create} does
     * (SCRUM-462). Anything else is TRANSFER_ORDER_NOT_EDITABLE; a stale version OPTIMISTIC_LOCK.
     */
    Transfer reviseDraft(UUID transferId, Revise command);

    PageResponse<Row> list(List<TransferStatus> statuses, UUID fromWarehouseId, UUID toWarehouseId, String number,
                           int page, int size);

    Transfer submit(UUID transferId, UUID userId);

    Transfer approve(UUID transferId, UUID approverId);

    Transfer reject(UUID transferId, UUID approverId, String reason);

    Transfer startPicking(UUID transferId);

    Transfer cancelPicking(UUID transferId);

    /** {@code shippedByLine}: line id → units that left; a line not named ships nothing. */
    Transfer dispatch(UUID transferId, UUID userId, Map<UUID, Integer> shippedByLine);

    Transfer cancel(UUID transferId, String reason);
}
