package com.stockflow.fulfillment.internal.entity;

import com.stockflow.fulfillment.api.PickDestination;
import com.stockflow.fulfillment.internal.domain.PickStatus;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.util.UUID;
import java.time.Instant;

/**
 * JPA mapping of a pick (table {@code fulfillment.pick}), and the task's own status moves (kltn-docs 07 §5.1). Not the
 * domain model of its lines, which is {@code PickList}.
 *
 * <p>Since team plan 4C-5 a task has a destination ({@link PickDestination}): DISPATCH deducts each carton as it is
 * scanned and completes PICKED; STAGING moves each carton to {@link #getStagingLocationCode the staging area} and
 * completes STAGED, and the warehouse manager later moves the whole task to dispatch ({@link #moveToDispatch}). The
 * assignee and the destination change only while PENDING (07 BR-08, {@code tg_pick_pending_edits}).</p>
 */
@Entity
@Table(name = "pick", schema = "fulfillment")
public class PickJpaEntity extends BaseEntity {

    @Column(name = "wave_id")
    private UUID waveId;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "delivery_batch_id", updatable = false)
    private UUID deliveryBatchId;

    /**
     * WF-05 fix plan, section 2: the delivery failure case whose goods this task picks again - a second task of an
     * order and batch that were picked once already ({@code uk_fulfillment_pick_order_batch} counts it apart); null
     * for the first pick. {@code tg_pick_redelivery_case}: a case of this order and batch, released for redelivery.
     */
    @Column(name = "redelivery_case_id", updatable = false)
    private UUID redeliveryCaseId;

    /** The order's number when the task was opened, so a pick list needs no call to the orders module (SCRUM-466). */
    @Column(name = "order_number", nullable = false, length = 32)
    private String orderNumber;

    /** The warehouse the goods are picked from; the assignee must work there (BR-SEC-002, SCRUM-466). */
    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private PickStatus status;
    @Column(name = "assigned_user_id") private UUID assignedUserId;
    @Column(name = "assigned_at") private Instant assignedAt;
    @Column(name = "started_at") private Instant startedAt;
    @Column(name = "picked_at") private Instant pickedAt;
    /** Goods already taken off their bins for a cancelled order: someone puts them back (17 BR-03). */
    @Column(name = "needs_put_back", nullable = false) private boolean needsPutBack;
    /** 4C-5: where the cartons go; {@code ck_pick_destination}. */
    @Enumerated(EnumType.STRING)
    @Column(name = "destination", nullable = false, length = 16)
    private PickDestination destination = PickDestination.DISPATCH;
    /** 4C-5: the STAGING area of the task's warehouse a STAGING task moves its cartons to; null otherwise. */
    @Column(name = "staging_location_code", length = 64) private String stagingLocationCode;
    /** 4C-5: when the task last became STAGED - every line in the staging area. */
    @Column(name = "staged_at") private Instant stagedAt;
    /** 4C-5: when, and by whom, the staged task was moved to dispatch (07 §4.3 step 8). */
    @Column(name = "moved_to_dispatch_at") private Instant movedToDispatchAt;
    @Column(name = "moved_to_dispatch_by") private UUID movedToDispatchBy;

    protected PickJpaEntity() {
    }

    public PickJpaEntity(UUID id, UUID waveId, UUID orderId, String orderNumber, UUID warehouseId,
                         PickStatus status) {
        super(id);
        this.waveId = waveId;
        this.orderId = orderId;
        this.orderNumber = java.util.Objects.requireNonNull(orderNumber, "orderNumber");
        this.warehouseId = java.util.Objects.requireNonNull(warehouseId, "warehouseId");
        this.status = status;
    }

    /** A new task with its destination: {@code stagingLocationCode} for a STAGING task, null for a DISPATCH one. */
    public PickJpaEntity(UUID id, UUID waveId, UUID orderId, String orderNumber, UUID warehouseId,
                         PickStatus status, PickDestination destination, String stagingLocationCode) {
        this(id, waveId, orderId, orderNumber, warehouseId, status);
        applyDestination(destination, stagingLocationCode);
    }

    /**
     * Who picks it: only while PENDING (kltn-docs 07 §4.1 step 3, BR-08). Once picking started, the lines are the
     * assignee's to scan - only they may (BR-08) - and handing a half-walked task to someone else is not the docs'
     * flow ({@code tg_pick_pending_edits} says the same).
     */
    public void assign(UUID userId, Instant now) {
        requirePending("assigned");
        assignedUserId = java.util.Objects.requireNonNull(userId);
        assignedAt = java.util.Objects.requireNonNull(now);
    }

    /**
     * Where the cartons go: only while PENDING (07 §4.1 step 3, BR-08). The caller checked that
     * {@code stagingLocationCode} is an active staging area of the task's warehouse ({@code tg_pick_pending_edits}
     * checks it is one); it is dropped for a DISPATCH task.
     */
    public void changeDestination(PickDestination destination, String stagingLocationCode) {
        requirePending("given another destination");
        applyDestination(destination, stagingLocationCode);
    }

    private void applyDestination(PickDestination destination, String stagingLocationCode) {
        this.destination = java.util.Objects.requireNonNull(destination, "destination");
        if (destination == PickDestination.STAGING) {
            this.stagingLocationCode = java.util.Objects.requireNonNull(stagingLocationCode, "stagingLocationCode");
        } else {
            this.stagingLocationCode = null;
        }
    }

    private void requirePending(String what) {
        if (status != PickStatus.PENDING) {
            throw new BusinessException(ErrorCode.PICK_TASK_STATE_CONFLICT,
                    "A pick is %s only while PENDING; it is %s".formatted(what, status));
        }
    }

    /**
     * Where a line confirmed now takes its carton: the staging area, for a STAGING task not moved to dispatch yet; null
     * when it is deducted on the spot - a DISPATCH task, or a STAGING task already moved to dispatch, whose top-up lines
     * go straight there (07 §4.3 step 7).
     */
    public String confirmsInto() {
        return destination == PickDestination.STAGING && movedToDispatchAt == null ? stagingLocationCode : null;
    }
    /**
     * The assignee starts walking. Starting a task that is already PICKING is answered, not refused: a
     * scanner retrying the call, or a task opened before pick lines existed whose lines are generated
     * now (SCRUM-267), both reach here.
     */
    public void start(UUID actor, Instant now) {
        requireAssigned(actor);
        if (status == PickStatus.PICKING) {
            return;
        }
        if (status != PickStatus.PENDING) {
            throw new BusinessException(ErrorCode.PICK_TASK_STATE_CONFLICT,
                    "Picking starts from PENDING; the task is " + status);
        }
        status = PickStatus.PICKING; startedAt = now;
    }
    /**
     * Lines are checked by {@code PickList.requireAllPicked} before this; the entity checks the status. A task whose
     * lines were staged ({@link #confirmsInto} not null) is STAGED: its cartons wait in the staging area for the
     * warehouse manager (07 §4.2 step 6); any other is PICKED.
     */
    public void complete(UUID actor, Instant now) {
        requireAssigned(actor);
        if (status != PickStatus.PICKING) {
            throw new BusinessException(ErrorCode.PICK_TASK_STATE_CONFLICT,
                    "Picking completes from PICKING; the task is " + status);
        }
        if (confirmsInto() != null) {
            status = PickStatus.STAGED; stagedAt = now;
        } else {
            status = PickStatus.PICKED; pickedAt = now;
        }
    }

    /**
     * The warehouse manager moved the whole STAGED task to dispatch (07 §4.3 step 8): PICKED now, its lines deducted by
     * the caller in the same transaction; packing (checking and labelling) may start. Lines added later go straight to
     * dispatch ({@link #confirmsInto}).
     */
    public void moveToDispatch(UUID actor, Instant now) {
        if (status != PickStatus.STAGED) {
            throw new BusinessException(ErrorCode.PICK_TASK_STATE_CONFLICT,
                    "A task moves to dispatch from STAGED; it is " + status);
        }
        status = PickStatus.PICKED;
        pickedAt = java.util.Objects.requireNonNull(now, "now");
        movedToDispatchAt = now;
        movedToDispatchBy = java.util.Objects.requireNonNull(actor, "actor");
    }

    /**
     * Back to PICKING, for the same assignee, until the new lines are picked and the task completes again:
     * <ul>
     *   <li>from PICKED, the order's remaining cartons were allocated and added to the task (a top-up, 07 §4.3 step 7).
     *       Packing waits meanwhile - it works on a PICKED task only;</li>
     *   <li>from STAGED, a top-up: the new cartons join the others in the staging area.</li>
     * </ul>
     */
    public void reopen() {
        if (status != PickStatus.PICKED && status != PickStatus.STAGED) {
            throw new BusinessException(ErrorCode.PICK_TASK_STATE_CONFLICT,
                    "Only a picked or staged task goes back to picking; the task is " + status);
        }
        if (status == PickStatus.PICKED) {
            pickedAt = null;
        }
        status = PickStatus.PICKING;
    }
    /**
     * The order was cancelled (SCRUM-460). Once picking has started, goods may be off their bins, so
     * the pick is flagged for a put-back. Idempotent.
     *
     * @return false when it was already cancelled
     */
    public boolean cancel() {
        if (status == PickStatus.CANCELLED) {
            return false;
        }
        needsPutBack = status != PickStatus.PENDING;
        status = PickStatus.CANCELLED;
        return true;
    }
    public void requireAssigned(UUID actor) {
        if (actor == null || !actor.equals(assignedUserId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Fulfillment task is not assigned to this user");
        }
    }

    public UUID getWaveId() { return waveId; }
    public UUID getOrderId() { return orderId; }

    /** 4B-8 (07 BR-07): the released delivery batch this task picks; null for an order without a schedule. */
    public UUID getDeliveryBatchId() { return deliveryBatchId; }

    public UUID getRedeliveryCaseId() { return redeliveryCaseId; }

    /** The task picks again the goods of this delivery failure case (set once, when the task is opened). */
    public PickJpaEntity forRedelivery(UUID caseId) {
        this.redeliveryCaseId = caseId;
        return this;
    }

    /** Set once, when the task is opened. */
    public PickJpaEntity forDeliveryBatch(UUID batchId) {
        this.deliveryBatchId = batchId;
        return this;
    }
    public String getOrderNumber() { return orderNumber; }
    public UUID getWarehouseId() { return warehouseId; }
    public PickStatus getStatus() { return status; }
    public UUID getAssignedUserId() { return assignedUserId; }
    public Instant getAssignedAt() { return assignedAt; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getPickedAt() { return pickedAt; }
    public boolean isNeedsPutBack() { return needsPutBack; }
    public PickDestination getDestination() { return destination; }
    public String getStagingLocationCode() { return stagingLocationCode; }
    public Instant getStagedAt() { return stagedAt; }
    public Instant getMovedToDispatchAt() { return movedToDispatchAt; }
    public UUID getMovedToDispatchBy() { return movedToDispatchBy; }
}
