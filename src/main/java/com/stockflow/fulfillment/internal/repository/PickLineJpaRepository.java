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

    Page<PickLineJpaEntity> findByPickId(UUID pickId, Pageable pageable);

    /** The one line not CANCELLED that stands on the hold (uk_pick_line_reservation_live); read without a lock. */
    @org.springframework.data.jpa.repository.Query("select l from PickLineJpaEntity l where l.reservationId = :reservationId"
            + " and l.status <> com.stockflow.fulfillment.internal.domain.PickLineStatus.CANCELLED")
    Optional<PickLineJpaEntity> findLiveByReservationId(
            @org.springframework.data.repository.query.Param("reservationId") UUID reservationId);
}
