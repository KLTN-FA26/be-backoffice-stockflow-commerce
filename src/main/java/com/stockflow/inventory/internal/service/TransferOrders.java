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

    /** @param deliveryMode LP2-8: null is FULL_TRUCKLOAD */
    record Create(UUID fromWarehouseId, UUID toWarehouseId, TransferReason reason, LocalDate expectedDate,
                  List<NewLine> lines, com.stockflow.inventory.api.TransferDeliveryMode deliveryMode) {

        public Create(UUID fromWarehouseId, UUID toWarehouseId, TransferReason reason, LocalDate expectedDate,
                      List<NewLine> lines) {
            this(fromWarehouseId, toWarehouseId, reason, expectedDate, lines, null);
        }
    }

    /**
     * The whole DRAFT as the planner now wants it; {@code version} is the one they read.
     *
     * @param deliveryMode null keeps it
     */
    record Revise(long version, UUID fromWarehouseId, UUID toWarehouseId, TransferReason reason,
                  LocalDate expectedDate, List<NewLine> lines,
                  com.stockflow.inventory.api.TransferDeliveryMode deliveryMode) {

        public Revise(long version, UUID fromWarehouseId, UUID toWarehouseId, TransferReason reason,
                      LocalDate expectedDate, List<NewLine> lines) {
            this(version, fromWarehouseId, toWarehouseId, reason, expectedDate, lines, null);
        }
    }

    record Line(UUID lineId, int lineNo, String sku, String lotNumber, int requested, int shipped) {
    }

    /**
     * @param deliveryMode LP2-8: FULL_TRUCKLOAD (packed, load-planned, dispatched by its plan's last vehicle) or PARCEL
     * @param packedAt     its packing completed: ready for a load plan (SCRUM-287 PR T)
     * @param goodsLeftAt  goods of it first left on a load plan's vehicle: picking is no longer cancelled (10 BR-04)
     * @param rejectedBy      the approver of its last rejection (10 §5), null when it was never rejected
     * @param rejectedAt      when that rejection was made
     * @param rejectionReason why: what the planner reads in DRAFT before changing and resubmitting it
     */
    record Transfer(UUID transferId, String number, UUID fromWarehouseId, UUID toWarehouseId, TransferStatus status,
                    TransferReason reason, LocalDate expectedDate, List<Line> lines, UUID submittedBy,
                    Instant submittedAt, UUID approvedBy, Instant approvedAt, UUID dispatchedBy, Instant dispatchedAt,
                    String cancelReason, Instant createdAt, long version,
                    com.stockflow.inventory.api.TransferDeliveryMode deliveryMode, Instant packedAt,
                    Instant goodsLeftAt, UUID rejectedBy, Instant rejectedAt, String rejectionReason) {
    }

    /** @param deliveryMode LP2-8; @param packedAt its packing completed - ready for a load plan (SCRUM-287 PR T) */
    record Row(UUID transferId, String number, UUID fromWarehouseId, UUID toWarehouseId, TransferStatus status,
               LocalDate expectedDate, Instant createdAt, Instant dispatchedAt,
               com.stockflow.inventory.api.TransferDeliveryMode deliveryMode, Instant packedAt) {
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
