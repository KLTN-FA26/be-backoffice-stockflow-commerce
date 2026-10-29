package com.stockflow.fulfillment.internal.entity;

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

/** JPA mapping of a pick (table {@code fulfillment.pick}). Not the domain model. STARTER ENTITY. */
@Entity
@Table(name = "pick", schema = "fulfillment")
public class PickJpaEntity extends BaseEntity {

    @Column(name = "wave_id")
    private UUID waveId;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

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
    /**
     * Who picks it. While PENDING, and again while PICKING: a picker who went home, or a task reopened for the lines a
     * damage report made after its picker had finished, is handed to someone who is here; the lines picked stay
     * picked by whoever picked them.
     */
    public void assign(UUID userId, Instant now) {
        if (status != PickStatus.PENDING && status != PickStatus.PICKING) {
            throw new BusinessException(ErrorCode.PICK_TASK_STATE_CONFLICT,
                    "A pick is assigned while pending or being picked; it is " + status);
        }
        assignedUserId = java.util.Objects.requireNonNull(userId);
        assignedAt = java.util.Objects.requireNonNull(now);
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
    /** Lines are checked by {@code PickList.requireAllPicked} before this; the entity checks the status. */
    public void complete(UUID actor, Instant now) {
        requireAssigned(actor);
        if (status != PickStatus.PICKING) {
            throw new BusinessException(ErrorCode.PICK_TASK_STATE_CONFLICT,
                    "Picking completes from PICKING; the task is " + status);
        }
        status = PickStatus.PICKED; pickedAt = now;
    }
    /**
     * Units a line brought to packing were found damaged there, and what replaces them is picked again (R8): back to
     * PICKING, for the same assignee, until the new lines are picked and the task completes again. Packing waits
     * meanwhile - it works on a PICKED task only.
     */
    public void reopen() {
        if (status != PickStatus.PICKED) {
            throw new BusinessException(ErrorCode.PICK_TASK_STATE_CONFLICT,
                    "Only a picked task goes back to picking; the task is " + status);
        }
        status = PickStatus.PICKING;
        pickedAt = null;
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
    public String getOrderNumber() { return orderNumber; }
    public UUID getWarehouseId() { return warehouseId; }
    public PickStatus getStatus() { return status; }
    public UUID getAssignedUserId() { return assignedUserId; }
    public Instant getAssignedAt() { return assignedAt; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getPickedAt() { return pickedAt; }
    public boolean isNeedsPutBack() { return needsPutBack; }
}
