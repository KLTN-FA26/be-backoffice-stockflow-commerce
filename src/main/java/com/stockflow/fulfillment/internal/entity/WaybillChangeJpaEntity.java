package com.stockflow.fulfillment.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One change of a load's waybill number (table {@code fulfillment.waybill_change}; kltn-docs 09 §4.4, BR-24: "mọi lần
 * đổi lưu lịch sử - mã cũ → mới, nguồn, người, thời điểm, lý do"). Append-only, here (every column insert-only) and by
 * trigger. {@code newWaybillNo} is null when a cancelled booking took the number with it; {@code changedBy} is null
 * for a webhook, which is nobody.
 */
@Entity
@Table(name = "waybill_change", schema = "fulfillment")
public class WaybillChangeJpaEntity extends BaseEntity {

    @Column(name = "load_id", nullable = false, updatable = false) private UUID loadId;
    @Column(name = "old_waybill_no", length = 64, updatable = false) private String oldWaybillNo;
    @Column(name = "new_waybill_no", length = 64, updatable = false) private String newWaybillNo;
    @Column(name = "source", nullable = false, length = 16, updatable = false) private String source;
    @Column(name = "changed_by", updatable = false) private UUID changedBy;
    @Column(name = "changed_at", nullable = false, updatable = false) private Instant changedAt;
    @Column(name = "reason", length = 500, updatable = false) private String reason;

    protected WaybillChangeJpaEntity() {
    }

    public WaybillChangeJpaEntity(UUID id, UUID loadId, String oldWaybillNo, String newWaybillNo, String source,
                                  UUID changedBy, Instant changedAt, String reason) {
        super(id);
        this.loadId = loadId;
        this.oldWaybillNo = oldWaybillNo;
        this.newWaybillNo = newWaybillNo;
        this.source = source;
        this.changedBy = changedBy;
        this.changedAt = changedAt;
        this.reason = reason;
    }

    public UUID getLoadId() { return loadId; }
    public String getOldWaybillNo() { return oldWaybillNo; }
    public String getNewWaybillNo() { return newWaybillNo; }
    public String getSource() { return source; }
    public UUID getChangedBy() { return changedBy; }
    public Instant getChangedAt() { return changedAt; }
    public String getReason() { return reason; }
}
