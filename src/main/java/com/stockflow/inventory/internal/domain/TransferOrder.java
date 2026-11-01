package com.stockflow.inventory.internal.domain;

import com.stockflow.common.domain.AggregateRoot;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.inventory.api.TransferDeliveryMode;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * A transfer of stock from one warehouse to another (docs 10, SCRUM-326/327).
 *
 * <p>Approval follows a quantity threshold: at or under it the person who submits approves in the
 * same step, above it someone else must ({@code ck_transfer_order_four_eyes} says it again). A
 * self-approved transfer records that person as the approver and no separate submitter — the
 * table's four-eyes check compares two different people, and one person did both.</p>
 *
 * <p>While DRAFT — new, or sent back by a rejection — the planner may change everything: warehouses,
 * reason, date and lines ({@link #reviseDraft}, SCRUM-462, kltn-docs 10 §5).
 * {@code tg_transfer_order_draft_header} and {@code tg_transfer_order_line_draft_content} say it again.</p>
 *
 * <p>Dispatch is the point of no return: the stock leaves the source and is owned by the transfer
 * until the destination counts it in. Before dispatch it can be cancelled; after, only closed.</p>
 *
 * <p>SCRUM-287 PR T (LP2-8, kltn-docs 10 step 5): a transfer travels as a {@link TransferDeliveryMode#FULL_TRUCKLOAD}
 * by default - packed at the station ({@link #beginPacking}, {@link #markPacked}), its trip load-planned, dispatched at
 * its plan's last vehicle's signature. Goods of it may leave on an earlier vehicle of that plan ({@link
 * #recordGoodsLeft}); from then its picking is no longer cancelled (BR-04: cancelled only before it left).</p>
 */
public final class TransferOrder extends AggregateRoot {

    private final UUID id;
    private final String number;
    private UUID fromWarehouseId;
    private UUID toWarehouseId;
    private TransferReason reason;
    private LocalDate expectedDate;
    private final String note;
    private final List<TransferLine> lines;

    private TransferStatus status;
    private UUID submittedBy;
    private Instant submittedAt;
    private UUID approvedBy;
    private Instant approvedAt;
    private UUID dispatchedBy;
    private Instant dispatchedAt;
    private String cancelReason;
    private TransferDeliveryMode deliveryMode = TransferDeliveryMode.FULL_TRUCKLOAD;
    private Instant packedAt;
    private Instant goodsLeftAt;
    private UUID rejectedBy;
    private Instant rejectedAt;
    private String rejectionReason;
    private final long version;
    private final Instant createdAt;

    public TransferOrder(UUID id, String number, UUID fromWarehouseId, UUID toWarehouseId, TransferReason reason,
                         LocalDate expectedDate, String note, List<TransferLine> lines, TransferStatus status,
                         UUID submittedBy, Instant submittedAt, UUID approvedBy, Instant approvedAt,
                         UUID dispatchedBy, Instant dispatchedAt, String cancelReason, long version, Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.number = Objects.requireNonNull(number, "number");
        this.fromWarehouseId = Objects.requireNonNull(fromWarehouseId, "fromWarehouseId");
        this.toWarehouseId = Objects.requireNonNull(toWarehouseId, "toWarehouseId");
        this.reason = reason;
        this.expectedDate = expectedDate;
        this.note = note;
        this.lines = new ArrayList<>(Objects.requireNonNull(lines, "lines"));
        this.status = Objects.requireNonNull(status, "status");
        this.submittedBy = submittedBy;
        this.submittedAt = submittedAt;
        this.approvedBy = approvedBy;
        this.approvedAt = approvedAt;
        this.dispatchedBy = dispatchedBy;
        this.dispatchedAt = dispatchedAt;
        this.cancelReason = cancelReason;
        this.version = version;
        this.createdAt = createdAt;
        requireValid(fromWarehouseId, toWarehouseId, this.lines);
    }

    private static void requireValid(UUID fromWarehouseId, UUID toWarehouseId, List<TransferLine> lines) {
        if (fromWarehouseId.equals(toWarehouseId)) {
            // BR-01: inside one warehouse it is a move, not a transfer.
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "Source and destination warehouse must differ; within one warehouse, move the stock instead");
        }
        if (lines.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A transfer order needs at least one line");
        }
        Set<String> keys = new HashSet<>();
        for (TransferLine line : lines) {
            if (!keys.add(line.sku().code() + "|" + Objects.toString(line.lotNumber(), ""))) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "%s%s appears twice; put the whole quantity on one line".formatted(
                                line.sku(), line.lotNumber() == null ? "" : " lot " + line.lotNumber()));
            }
        }
    }

    /**
     * The planner's new version of a DRAFT: warehouses, reason, date and lines all replaced, checked
     * as a new transfer is (BR-01, one line per SKU and lot). Anything but DRAFT is
     * TRANSFER_ORDER_NOT_EDITABLE.
     */
    public void reviseDraft(UUID fromWarehouseId, UUID toWarehouseId, TransferReason reason, LocalDate expectedDate,
                            List<TransferLine> lines) {
        if (status != TransferStatus.DRAFT) {
            throw new BusinessException(ErrorCode.TRANSFER_ORDER_NOT_EDITABLE,
                    "Transfer %s is %s".formatted(number, status));
        }
        Objects.requireNonNull(fromWarehouseId, "fromWarehouseId");
        Objects.requireNonNull(toWarehouseId, "toWarehouseId");
        List<TransferLine> replacement = List.copyOf(Objects.requireNonNull(lines, "lines"));
        requireValid(fromWarehouseId, toWarehouseId, replacement);
        this.fromWarehouseId = fromWarehouseId;
        this.toWarehouseId = toWarehouseId;
        this.reason = reason;
        this.expectedDate = expectedDate;
        this.lines.clear();
        this.lines.addAll(replacement);
    }

    /** Read back from the table: how it travels and how far its packing and loading got (SCRUM-287 PR T). */
    public TransferOrder restoreFulfillment(TransferDeliveryMode deliveryMode, Instant packedAt, Instant goodsLeftAt) {
        this.deliveryMode = deliveryMode == null ? TransferDeliveryMode.FULL_TRUCKLOAD : deliveryMode;
        this.packedAt = packedAt;
        this.goodsLeftAt = goodsLeftAt;
        return this;
    }

    /** Read back from the table: the last rejection, who made it, when, and why (kltn-docs 10 §5, issue 206). */
    public TransferOrder restoreRejection(UUID rejectedBy, Instant rejectedAt, String rejectionReason) {
        this.rejectedBy = rejectedBy;
        this.rejectedAt = rejectedAt;
        this.rejectionReason = rejectionReason;
        return this;
    }

    /** LP2-8: how it travels; chosen while DRAFT, as its warehouses are. Null keeps it. */
    public void travelAs(TransferDeliveryMode mode) {
        if (mode == null || mode == deliveryMode) {
            return;
        }
        if (status != TransferStatus.DRAFT) {
            throw new BusinessException(ErrorCode.TRANSFER_ORDER_NOT_EDITABLE,
                    "Transfer %s is %s; its delivery mode is chosen while DRAFT".formatted(number, status));
        }
        this.deliveryMode = mode;
    }

    public static TransferOrder draft(UUID id, String number, UUID fromWarehouseId, UUID toWarehouseId,
                                      TransferReason reason, LocalDate expectedDate, String note,
                                      List<TransferLine> lines, Instant now) {
        return new TransferOrder(id, number, fromWarehouseId, toWarehouseId, reason, expectedDate, note, lines,
                TransferStatus.DRAFT, null, null, null, null, null, null, null, 0L, now);
    }

    public int totalRequested() {
        return lines.stream().mapToInt(TransferLine::requested).sum();
    }

    /** At or under {@code threshold} units the submitter approves in the same step. */
    public void submit(UUID userId, int threshold, Instant now) {
        Objects.requireNonNull(userId, "userId");
        if (totalRequested() <= threshold) {
            moveTo(TransferStatus.APPROVED);
            this.submittedBy = null;
            this.submittedAt = null;
            this.approvedBy = userId;
            this.approvedAt = now;
        } else {
            moveTo(TransferStatus.PENDING_APPROVAL);
            this.submittedBy = userId;
            this.submittedAt = now;
        }
    }

    public void approve(UUID approverId, Instant now) {
        requireStatus(TransferStatus.PENDING_APPROVAL);
        if (Objects.equals(approverId, submittedBy)) {
            throw new BusinessException(ErrorCode.TRANSFER_SELF_APPROVAL,
                    "Transfer %s cannot be approved by the person who submitted it".formatted(number));
        }
        moveTo(TransferStatus.APPROVED);
        this.approvedBy = approverId;
        this.approvedAt = now;
    }

    /**
     * Back to DRAFT for the planner to change and resubmit. The reason, the rejecting person and the time are kept on
     * the order (kltn-docs 10 §5): the planner reads them while it is DRAFT. A later rejection replaces them - the
     * order shows its last one.
     */
    public void reject(UUID approverId, String reason, Instant now) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A rejection must say why");
        }
        requireStatus(TransferStatus.PENDING_APPROVAL);
        if (Objects.equals(approverId, submittedBy)) {
            throw new BusinessException(ErrorCode.TRANSFER_SELF_APPROVAL,
                    "Transfer %s cannot be decided by the person who submitted it".formatted(number));
        }
        moveTo(TransferStatus.DRAFT);
        this.submittedBy = null;
        this.submittedAt = null;
        this.rejectedBy = Objects.requireNonNull(approverId, "approverId");
        this.rejectedAt = Objects.requireNonNull(now, "now");
        this.rejectionReason = reason.trim();
    }

    public void startPicking() {
        moveTo(TransferStatus.PICKING);
    }

    /**
     * Picking stops and the goods go back (10 §5 Picking → Approved) - only while nothing of it left the source
     * ({@code TRANSFER_GOODS_LEFT}, BR-04). Its packing is undone with it.
     */
    public void cancelPicking() {
        requireStatus(TransferStatus.PICKING);
        if (goodsLeftAt != null) {
            throw new BusinessException(ErrorCode.TRANSFER_GOODS_LEFT, ("Goods of transfer %s left on a vehicle at %s; "
                    + "picking is no longer cancelled - the rest leaves with its load plan").formatted(number,
                    goodsLeftAt));
        }
        moveTo(TransferStatus.APPROVED);
        this.packedAt = null;
    }

    /**
     * The source warehouse packs it for a load plan (10 step 5, LP2-8): an APPROVED transfer starts picking, one
     * PICKING already stays. Only a full truckload is packed this way ({@code TRANSFER_NOT_FULL_TRUCKLOAD}).
     *
     * @return whether it changed
     */
    public boolean beginPacking() {
        if (deliveryMode != TransferDeliveryMode.FULL_TRUCKLOAD) {
            throw new BusinessException(ErrorCode.TRANSFER_NOT_FULL_TRUCKLOAD, ("Transfer %s travels as %s: it is "
                    + "dispatched by hand, without a load plan").formatted(number, deliveryMode));
        }
        if (status == TransferStatus.PICKING) {
            return false;
        }
        if (status != TransferStatus.APPROVED) {
            throw new BusinessException(ErrorCode.INVALID_TRANSFER_TRANSITION,
                    "Transfer %s is %s; it is packed once approved".formatted(number, status));
        }
        moveTo(TransferStatus.PICKING);
        return true;
    }

    /** Its packing completed (10 step 5): ready for a load plan. */
    public void markPacked(Instant at) {
        requireStatus(TransferStatus.PICKING);
        this.packedAt = Objects.requireNonNull(at, "at");
    }

    /** Goods of it left on a load plan's vehicle; the first time is kept. */
    public boolean recordGoodsLeft(Instant at) {
        requireStatus(TransferStatus.PICKING);
        if (goodsLeftAt != null) {
            return false;
        }
        this.goodsLeftAt = Objects.requireNonNull(at, "at");
        return true;
    }

    /**
     * The goods left the source. {@code shippedByLine} says how many of each line went; a line not
     * named ships nothing. At least one unit must ship — an empty truck is not a dispatch.
     */
    public void dispatch(UUID userId, Map<UUID, Integer> shippedByLine, Instant now) {
        requireStatus(TransferStatus.PICKING);
        for (UUID lineId : shippedByLine.keySet()) {
            if (lines.stream().noneMatch(line -> line.id().equals(lineId))) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "Line %s is not on transfer %s".formatted(lineId, number));
            }
        }
        int total = 0;
        for (TransferLine line : lines) {
            int quantity = shippedByLine.getOrDefault(line.id(), 0);
            if (quantity < 0 || quantity > line.requested()) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "Line %d may ship 0..%d, not %d".formatted(line.lineNo(), line.requested(), quantity));
            }
            line.ship(quantity);
            total += quantity;
        }
        if (total == 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A dispatch ships at least one unit");
        }
        moveTo(TransferStatus.IN_TRANSIT);
        this.dispatchedBy = Objects.requireNonNull(userId, "userId");
        this.dispatchedAt = now;
    }

    public void cancel(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A cancellation must say why");
        }
        moveTo(TransferStatus.CANCELLED);
        this.cancelReason = reason.trim();
    }

    private void requireStatus(TransferStatus expected) {
        if (status != expected) {
            throw new BusinessException(ErrorCode.INVALID_TRANSFER_TRANSITION,
                    "Transfer %s is %s, not %s".formatted(number, status, expected));
        }
    }

    private void moveTo(TransferStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new BusinessException(ErrorCode.INVALID_TRANSFER_TRANSITION,
                    "Transfer %s cannot go from %s to %s".formatted(number, status, target));
        }
        this.status = target;
    }

    public UUID id() { return id; }
    public String number() { return number; }
    public UUID fromWarehouseId() { return fromWarehouseId; }
    public UUID toWarehouseId() { return toWarehouseId; }
    public TransferReason reason() { return reason; }
    public LocalDate expectedDate() { return expectedDate; }
    public String note() { return note; }
    public List<TransferLine> lines() { return java.util.Collections.unmodifiableList(lines); }
    public TransferStatus status() { return status; }
    public UUID submittedBy() { return submittedBy; }
    public Instant submittedAt() { return submittedAt; }
    public UUID approvedBy() { return approvedBy; }
    public Instant approvedAt() { return approvedAt; }
    public UUID dispatchedBy() { return dispatchedBy; }
    public Instant dispatchedAt() { return dispatchedAt; }
    public String cancelReason() { return cancelReason; }
    public TransferDeliveryMode deliveryMode() { return deliveryMode; }
    public Instant packedAt() { return packedAt; }
    public Instant goodsLeftAt() { return goodsLeftAt; }
    public UUID rejectedBy() { return rejectedBy; }
    public Instant rejectedAt() { return rejectedAt; }
    public String rejectionReason() { return rejectionReason; }
    public long version() { return version; }
    public Instant createdAt() { return createdAt; }
}
