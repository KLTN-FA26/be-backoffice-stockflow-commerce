package com.stockflow.fulfillment.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import com.stockflow.fulfillment.internal.domain.DesignVerificationResult;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Immutable evidence of every packing-time integrity attempt. */
@Entity
@Table(name = "design_verification", schema = "fulfillment")
public class DesignVerificationJpaEntity extends BaseEntity {
    @Column(name = "pack_id", nullable = false, updatable = false) private UUID packId;
    @Column(name = "order_id", nullable = false, updatable = false) private UUID orderId;
    @Column(name = "order_line_id", nullable = false, updatable = false) private UUID orderLineId;
    @Column(name = "snapshot_id", nullable = false, updatable = false) private UUID snapshotId;
    @Column(name = "expected_checksum", nullable = false, updatable = false, length = 64) private String expectedChecksum;
    @Column(name = "actual_checksum", nullable = false, updatable = false, length = 64) private String actualChecksum;
    @Enumerated(EnumType.STRING)
    @Column(name = "result", nullable = false, updatable = false, length = 16) private DesignVerificationResult result;
    @Column(name = "verified_by", nullable = false, updatable = false) private UUID verifiedBy;
    @Column(name = "verified_at", nullable = false, updatable = false) private Instant verifiedAt;
    protected DesignVerificationJpaEntity() { }
    public DesignVerificationJpaEntity(UUID id, UUID packId, UUID orderId, UUID orderLineId, UUID snapshotId,
            String expected, String actual, DesignVerificationResult result, UUID actor, Instant now) {
        super(id); this.packId = packId; this.orderId = orderId; this.orderLineId = orderLineId;
        this.snapshotId = snapshotId; this.expectedChecksum = expected; this.actualChecksum = actual;
        this.result = result; this.verifiedBy = actor; this.verifiedAt = now;
    }
    public UUID getOrderLineId() { return orderLineId; }
    public UUID getSnapshotId() { return snapshotId; }
    public DesignVerificationResult getResult() { return result; }
    public String getExpectedChecksum() { return expectedChecksum; }
    public String getActualChecksum() { return actualChecksum; }
    public Instant getVerifiedAt() { return verifiedAt; }
}
