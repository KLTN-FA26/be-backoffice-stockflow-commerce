package com.stockflow.fulfillment.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.fulfillment.internal.entity.PickLineJpaEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence access for the lines of a pick task. Always read for one task, in walking order. */
public interface PickLineJpaRepository extends BaseJpaRepository<PickLineJpaEntity> {

    List<PickLineJpaEntity> findByPickIdOrderBySequenceNoAscIdAsc(UUID pickId);

    /** The lines that replace any of these removed lines (07 §4.4 step 10). */
    List<PickLineJpaEntity> findByReplacesLineIdIn(java.util.Collection<UUID> removedLineIds);

    Page<PickLineJpaEntity> findByPickId(UUID pickId, Pageable pageable);

    /** The one line not CANCELLED that stands on the hold (uk_pick_line_reservation_live); read without a lock. */
    @org.springframework.data.jpa.repository.Query("select l from PickLineJpaEntity l where l.reservationId = :reservationId"
            + " and l.status <> com.stockflow.fulfillment.internal.domain.PickLineStatus.CANCELLED")
    Optional<PickLineJpaEntity> findLiveByReservationId(
            @org.springframework.data.repository.query.Param("reservationId") UUID reservationId);

    /**
     * Of {@code lpns}, those picked for an order and still at the warehouse (kltn-docs 03 §4.1 step 4): on a PICKED line
     * of a task not cancelled, whose shipping carton was not removed at the check station and is in no shipment or in
     * one still READY_TO_DISPATCH. A carton picked again after it came back counts by its newest line.
     */
    @org.springframework.data.jpa.repository.Query("""
            select distinct l.lpn from PickLineJpaEntity l, PickJpaEntity p
             where p.id = l.pickId
               and l.lpn in :lpns
               and l.status = com.stockflow.fulfillment.internal.domain.PickLineStatus.PICKED
               and p.status <> com.stockflow.fulfillment.internal.domain.PickStatus.CANCELLED
               and not exists (select c.id from PackageJpaEntity c
                                where c.pickLineId = l.id
                                  and (c.status = com.stockflow.fulfillment.internal.domain.PackageStatus.REMOVED
                                       or c.shipmentId in (select s.id from ShipmentJpaEntity s
                                                            where s.status <> com.stockflow.fulfillment.internal.domain.ShipmentStatus.READY_TO_DISPATCH)))""")
    List<String> findPickedNotHandedOver(
            @org.springframework.data.repository.query.Param("lpns") java.util.Collection<String> lpns);
}
