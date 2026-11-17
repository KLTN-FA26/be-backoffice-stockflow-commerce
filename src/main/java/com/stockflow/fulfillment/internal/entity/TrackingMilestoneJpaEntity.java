package com.stockflow.fulfillment.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A milestone of a vehicle's journey as its carrier reported it (table {@code fulfillment.tracking_milestone};
 * kltn-docs 09 §4.5 step 9): picked up, on its way, returning, returned - one of each per load, the first report kept.
 * Written by the carrier-event applier ({@code TrackingMilestoneJpaRepository.recordFromCarrier}); append-only.
 */
@Entity
@Table(name = "tracking_milestone", schema = "fulfillment")
public class TrackingMilestoneJpaEntity extends BaseEntity {

    @Column(name = "load_id", nullable = false, updatable = false) private UUID loadId;
    @Column(name = "milestone", nullable = false, length = 16, updatable = false) private String milestone;
    @Column(name = "carrier_reported_at", nullable = false, updatable = false) private Instant carrierReportedAt;
    @Column(name = "source", nullable = false, length = 16, updatable = false) private String source;
    @Column(name = "carrier_event_id", updatable = false) private UUID carrierEventId;
    @Column(name = "recorded_at", nullable = false, updatable = false) private Instant recordedAt;

    protected TrackingMilestoneJpaEntity() {
    }

    public UUID getLoadId() { return loadId; }
    public String getMilestone() { return milestone; }
    public Instant getCarrierReportedAt() { return carrierReportedAt; }
    public String getSource() { return source; }
    public UUID getCarrierEventId() { return carrierEventId; }
    public Instant getRecordedAt() { return recordedAt; }
}
