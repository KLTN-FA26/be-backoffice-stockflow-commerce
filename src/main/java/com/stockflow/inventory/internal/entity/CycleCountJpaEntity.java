package com.stockflow.inventory.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import com.stockflow.inventory.internal.domain.CycleCountStatus;
import com.stockflow.inventory.internal.domain.CycleCountTrigger;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * {@code inventory.cycle_count}. What identifies a count (number, warehouse, trigger, blindness) is fixed at planning
 * and not updatable; status, assignee, timestamps and note are what the aggregate moves.
 */
@Entity
@Table(name = "cycle_count", schema = "inventory")
public class CycleCountJpaEntity extends BaseEntity {

    @Column(name = "count_number", nullable = false, length = 30, updatable = false)
    private String countNumber;

    @Column(name = "warehouse_id", nullable = false, updatable = false)
    private UUID warehouseId;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, length = 24, updatable = false)
    private CycleCountTrigger triggerType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private CycleCountStatus status;

    @Column(name = "is_blind", nullable = false, updatable = false)
    private boolean blind;

    @Column(name = "assigned_user_id")
    private UUID assignedUserId;

    @Column(name = "planned_date")
    private LocalDate plannedDate;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "note", length = 1000)
    private String note;

    // No REFRESH cascade: the pessimistic refresh of the header must not reload each line one by one (N+1).
    @OneToMany(mappedBy = "cycleCount", cascade = {CascadeType.PERSIST, CascadeType.MERGE, CascadeType.REMOVE},
            orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("locationCode ASC, sku ASC, id ASC")
    private List<CycleCountLineJpaEntity> lines = new ArrayList<>();

    protected CycleCountJpaEntity() {
    }

    public CycleCountJpaEntity(UUID id, String countNumber, UUID warehouseId, CycleCountTrigger triggerType,
                               boolean blind, LocalDate plannedDate) {
        super(id);
        this.countNumber = countNumber;
        this.warehouseId = warehouseId;
        this.triggerType = triggerType;
        this.blind = blind;
        this.plannedDate = plannedDate;
        this.status = CycleCountStatus.PLANNED;
    }

    public void apply(CycleCountStatus status, UUID assignedUserId, Instant startedAt, Instant completedAt,
                      String note) {
        this.status = status;
        this.assignedUserId = assignedUserId;
        this.startedAt = startedAt;
        this.completedAt = completedAt;
        this.note = note;
    }

    public void addLine(CycleCountLineJpaEntity line) {
        line.attachTo(this);
        lines.add(line);
    }

    public String getCountNumber() { return countNumber; }
    public UUID getWarehouseId() { return warehouseId; }
    public CycleCountTrigger getTriggerType() { return triggerType; }
    public CycleCountStatus getStatus() { return status; }
    public boolean isBlind() { return blind; }
    public UUID getAssignedUserId() { return assignedUserId; }
    public LocalDate getPlannedDate() { return plannedDate; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public String getNote() { return note; }
    public List<CycleCountLineJpaEntity> getLines() { return lines; }
}
