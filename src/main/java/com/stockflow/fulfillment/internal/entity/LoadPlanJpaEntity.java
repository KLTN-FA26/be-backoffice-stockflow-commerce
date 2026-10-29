package com.stockflow.fulfillment.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import com.stockflow.fulfillment.internal.domain.LoadPlan;
import com.stockflow.fulfillment.internal.domain.LoadPlanStatus;
import com.stockflow.fulfillment.internal.domain.UnloadReasonCode;
import com.stockflow.fulfillment.internal.domain.loadplanning.SelectionMode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * JPA mapping of a load plan's header (table {@code fulfillment.load_plan}; SCRUM-287 PR D). Not the domain model:
 * {@link LoadPlan} holds the rules; {@code LoadPlanStore} copies between the two.
 */
@Entity
@Table(name = "load_plan", schema = "fulfillment")
public class LoadPlanJpaEntity extends BaseEntity {

    @Column(name = "code", nullable = false, updatable = false, length = 30) private String code;
    @Column(name = "request_id", nullable = false, updatable = false) private UUID requestId;
    @Column(name = "warehouse_id", nullable = false, updatable = false) private UUID warehouseId;
    @Column(name = "source_type", nullable = false, updatable = false, length = 16) private String sourceType;
    @Enumerated(EnumType.STRING)
    @Column(name = "selection_mode", nullable = false, length = 24) private SelectionMode selectionMode;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16) private LoadPlanStatus status;
    @Column(name = "note", length = 500) private String note;
    @Column(name = "calculated_at") private Instant calculatedAt;
    @Column(name = "calculated_by") private UUID calculatedBy;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "solver_meta", columnDefinition = "jsonb") private Map<String, Object> solverMeta;
    @Column(name = "unplaced_acknowledged_at") private Instant acknowledgedAt;
    @Column(name = "unplaced_acknowledged_by") private UUID acknowledgedBy;
    @Column(name = "unplaced_ack_note", length = 500) private String acknowledgeNote;
    @Column(name = "confirmed_at") private Instant confirmedAt;
    @Column(name = "confirmed_by") private UUID confirmedBy;
    @Column(name = "reopened_at") private Instant reopenedAt;
    @Column(name = "reopened_by") private UUID reopenedBy;
    @Column(name = "reopen_reason", length = 500) private String reopenReason;
    @Column(name = "dispatched_at") private Instant dispatchedAt;
    @Column(name = "cancelled_at") private Instant cancelledAt;
    @Column(name = "cancelled_by") private UUID cancelledBy;
    @Column(name = "cancel_reason", length = 500) private String cancelReason;
    /** SCRUM-474 UNLOAD-DESIGN point 7: set only by a controlled cancel (V20261029007000). */
    @Enumerated(EnumType.STRING)
    @Column(name = "cancel_reason_code", length = 20) private UnloadReasonCode cancelReasonCode;
    @Column(name = "revised_at", nullable = false) private Instant revisedAt;

    protected LoadPlanJpaEntity() {
    }

    public LoadPlanJpaEntity(LoadPlan.Snapshot s) {
        super(s.id());
        this.code = s.code();
        this.requestId = s.requestId();
        this.warehouseId = s.warehouseId();
        this.sourceType = s.sourceType();
    }

    /** Everything that may change; {@code revisedAt} moves on every write so the version always does. */
    public void apply(LoadPlan.Snapshot s, Instant revisedAt) {
        this.selectionMode = s.mode();
        this.status = s.status();
        this.note = s.note();
        this.calculatedAt = s.calculatedAt();
        this.calculatedBy = s.calculatedBy();
        this.acknowledgedAt = s.acknowledgedAt();
        this.acknowledgedBy = s.acknowledgedBy();
        this.acknowledgeNote = s.acknowledgeNote();
        this.confirmedAt = s.confirmedAt();
        this.confirmedBy = s.confirmedBy();
        this.reopenedAt = s.reopenedAt();
        this.reopenedBy = s.reopenedBy();
        this.reopenReason = s.reopenReason();
        this.dispatchedAt = s.dispatchedAt();
        this.cancelledAt = s.cancelledAt();
        this.cancelledBy = s.cancelledBy();
        this.cancelReason = s.cancelReason();
        this.cancelReasonCode = s.cancelReasonCode();
        // Strictly later than the last write, so the row is always dirty and the version always moves.
        this.revisedAt = this.revisedAt != null && !revisedAt.isAfter(this.revisedAt)
                ? this.revisedAt.plusNanos(1_000) : revisedAt;
    }

    public void setSolverMeta(Map<String, Object> solverMeta) {
        this.solverMeta = solverMeta;
    }

    public String getCode() { return code; }
    public UUID getRequestId() { return requestId; }
    public UUID getWarehouseId() { return warehouseId; }
    public String getSourceType() { return sourceType; }
    public SelectionMode getSelectionMode() { return selectionMode; }
    public LoadPlanStatus getStatus() { return status; }
    public String getNote() { return note; }
    public Instant getCalculatedAt() { return calculatedAt; }
    public UUID getCalculatedBy() { return calculatedBy; }
    public Map<String, Object> getSolverMeta() { return solverMeta; }
    public Instant getAcknowledgedAt() { return acknowledgedAt; }
    public UUID getAcknowledgedBy() { return acknowledgedBy; }
    public String getAcknowledgeNote() { return acknowledgeNote; }
    public Instant getConfirmedAt() { return confirmedAt; }
    public UUID getConfirmedBy() { return confirmedBy; }
    public Instant getReopenedAt() { return reopenedAt; }
    public UUID getReopenedBy() { return reopenedBy; }
    public String getReopenReason() { return reopenReason; }
    public Instant getDispatchedAt() { return dispatchedAt; }
    public Instant getCancelledAt() { return cancelledAt; }
    public UUID getCancelledBy() { return cancelledBy; }
    public String getCancelReason() { return cancelReason; }
    public UnloadReasonCode getCancelReasonCode() { return cancelReasonCode; }
    public Instant getRevisedAt() { return revisedAt; }
}
