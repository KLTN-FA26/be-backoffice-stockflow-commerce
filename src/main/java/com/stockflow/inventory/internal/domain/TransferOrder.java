package com.stockflow.inventory.internal.domain;

import com.stockflow.common.domain.AggregateRoot;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

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
 * <p>Dispatch is the point of no return: the stock leaves the source and is owned by the transfer
 * until the destination counts it in. Before dispatch it can be cancelled; after, only closed.</p>
 */
public final class TransferOrder extends AggregateRoot {

    private final UUID id;
    private final String number;
    private final UUID fromWarehouseId;
    private final UUID toWarehouseId;
    private final TransferReason reason;
    private final LocalDate expectedDate;
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
        if (fromWarehouseId.equals(toWarehouseId)) {
            // BR-01: inside one warehouse it is a move, not a transfer.
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "Source and destination warehouse must differ; within one warehouse, move the stock instead");
        }
        if (this.lines.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A transfer order needs at least one line");
        }
        Set<String> keys = new HashSet<>();
        for (TransferLine line : this.lines) {
            if (!keys.add(line.sku().code() + "|" + Objects.toString(line.lotNumber(), ""))) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "%s%s appears twice; put the whole quantity on one line".formatted(
                                line.sku(), line.lotNumber() == null ? "" : " lot " + line.lotNumber()));
            }
        }
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

    /** Back to DRAFT for the planner to change and resubmit. */
    public void reject(UUID approverId) {
        requireStatus(TransferStatus.PENDING_APPROVAL);
        if (Objects.equals(approverId, submittedBy)) {
            throw new BusinessException(ErrorCode.TRANSFER_SELF_APPROVAL,
                    "Transfer %s cannot be decided by the person who submitted it".formatted(number));
        }
        moveTo(TransferStatus.DRAFT);
        this.submittedBy = null;
        this.submittedAt = null;
    }

    public void startPicking() {
        moveTo(TransferStatus.PICKING);
    }

    public void cancelPicking() {
        requireStatus(TransferStatus.PICKING);
        moveTo(TransferStatus.APPROVED);
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
    public long version() { return version; }
    public Instant createdAt() { return createdAt; }
}
