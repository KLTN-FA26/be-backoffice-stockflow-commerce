package com.stockflow.identity.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * {@code identity.staff_cover} (V20270128000110): who stands in for an absent salesperson. Statuses are stored as text
 * and named by the table's CHECKs. Only the cancellation columns are ever updated: a cover is never edited.
 */
@Entity
@Table(name = "staff_cover", schema = "identity")
public class StaffCoverJpaEntity extends BaseEntity {

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "cover_user_id", nullable = false, updatable = false)
    private UUID coverUserId;

    @Column(name = "from_date", nullable = false, updatable = false)
    private LocalDate fromDate;

    @Column(name = "to_date", nullable = false, updatable = false)
    private LocalDate toDate;

    @Column(name = "reason", length = 500, updatable = false)
    private String reason;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "arranged_by", updatable = false)
    private UUID arrangedBy;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancelled_by")
    private UUID cancelledBy;

    @Column(name = "cancel_reason", length = 32)
    private String cancelReason;

    protected StaffCoverJpaEntity() {
    }

    public StaffCoverJpaEntity(UUID id, UUID userId, UUID coverUserId, LocalDate fromDate, LocalDate toDate,
                               String reason, UUID arrangedBy, String status) {
        super(id);
        this.userId = userId;
        this.coverUserId = coverUserId;
        this.fromDate = fromDate;
        this.toDate = toDate;
        this.reason = reason;
        this.arrangedBy = arrangedBy;
        this.status = status;
    }

    public void applyStatus(String status, Instant cancelledAt, UUID cancelledBy, String cancelReason) {
        this.status = status;
        this.cancelledAt = cancelledAt;
        this.cancelledBy = cancelledBy;
        this.cancelReason = cancelReason;
    }

    public UUID getUserId() { return userId; }
    public UUID getCoverUserId() { return coverUserId; }
    public LocalDate getFromDate() { return fromDate; }
    public LocalDate getToDate() { return toDate; }
    public String getReason() { return reason; }
    public String getStatus() { return status; }
    public UUID getArrangedBy() { return arrangedBy; }
    public Instant getCancelledAt() { return cancelledAt; }
    public UUID getCancelledBy() { return cancelledBy; }
    public String getCancelReason() { return cancelReason; }
}
