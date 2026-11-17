package com.stockflow.fulfillment.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.fulfillment.internal.entity.TrackingMilestoneJpaEntity;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The journey of each vehicle as its carrier reports it (3-5). */
public interface TrackingMilestoneJpaRepository extends BaseJpaRepository<TrackingMilestoneJpaEntity> {

    /**
     * Records a milestone the carrier's webhook reported, unless the load has that one already (the first report is
     * kept: {@code uk_tracking_milestone}). One statement, so two reports racing record it once.
     *
     * @return 1 when recorded, 0 when the load had it
     */
    @Modifying
    @Query(value = """
            INSERT INTO fulfillment.tracking_milestone (id, load_id, milestone, carrier_reported_at, source,
                                                        carrier_event_id, recorded_at, version, created_at)
            VALUES (:id, :loadId, :milestone, :reportedAt, 'CARRIER_WEBHOOK', :eventId, :recordedAt, 0, :recordedAt)
            ON CONFLICT (load_id, milestone) DO NOTHING""", nativeQuery = true)
    int recordFromCarrier(@Param("id") UUID id, @Param("loadId") UUID loadId, @Param("milestone") String milestone,
                          @Param("reportedAt") Instant reportedAt, @Param("eventId") UUID eventId,
                          @Param("recordedAt") Instant recordedAt);

    List<TrackingMilestoneJpaEntity> findByLoadIdOrderByCarrierReportedAtAsc(UUID loadId);
}
