package com.stockflow.fulfillment.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.fulfillment.internal.entity.CarrierEventJpaEntity;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

/** Every call made to a carrier's webhook (3-5). */
public interface CarrierEventJpaRepository extends BaseJpaRepository<CarrierEventJpaEntity> {

    /**
     * The latest carrier time among the reports applied to the load as a whole (BR-27: an older one is outdated);
     * null when none was.
     */
    @Query("select max(e.carrierReportedAt) from CarrierEventJpaEntity e where e.loadId = :loadId "
            + "and e.shipmentId is null and e.result = com.stockflow.fulfillment.internal.domain.CarrierEventResult.APPLIED")
    Instant lastAppliedToLoad(@Param("loadId") UUID loadId);

    /** The latest carrier time among the reports applied to the shipment; null when none was. */
    @Query("select max(e.carrierReportedAt) from CarrierEventJpaEntity e where e.shipmentId = :shipmentId "
            + "and e.result = com.stockflow.fulfillment.internal.domain.CarrierEventResult.APPLIED")
    Instant lastAppliedToShipment(@Param("shipmentId") UUID shipmentId);

    /**
     * Whether another call with the same report was handled already (BR-27: a repeat is recorded once). Calls that
     * never reached a load - an unmatched report, a bad credential, one still being handled - do not count: a repeat of
     * those may well match now.
     */
    @Query("select count(e) > 0 from CarrierEventJpaEntity e where e.dedupeKey = :key and e.id <> :self "
            + "and e.result in (com.stockflow.fulfillment.internal.domain.CarrierEventResult.APPLIED, "
            + "com.stockflow.fulfillment.internal.domain.CarrierEventResult.DUPLICATE, "
            + "com.stockflow.fulfillment.internal.domain.CarrierEventResult.STALE, "
            + "com.stockflow.fulfillment.internal.domain.CarrierEventResult.IGNORED, "
            + "com.stockflow.fulfillment.internal.domain.CarrierEventResult.NEEDS_REVIEW)")
    boolean handledBefore(@Param("key") String key, @Param("self") UUID self);

    /**
     * Reports about one load are applied one at a time (an advisory lock on {@code carrier-event:<loadId>}, held to the
     * end of the transaction): two copies of a report, or an older report racing a newer one, are then decided against
     * what the other committed. Taken before the plan and load locks, by nothing else - so it adds no cycle to them.
     * {@code pg_advisory_xact_lock} returns void, hence the outer select.
     */
    @Query(value = "SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))) AS locked",
            nativeQuery = true)
    int lockReportsOf(@Param("key") String key);
}
