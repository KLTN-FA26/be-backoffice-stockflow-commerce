package com.stockflow.fulfillment.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.fulfillment.internal.entity.PickLineJpaEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/** Persistence access for the lines of a pick task. Always read for one task, in walking order. */
public interface PickLineJpaRepository extends BaseJpaRepository<PickLineJpaEntity> {

    List<PickLineJpaEntity> findByPickIdOrderBySequenceNoAscIdAsc(UUID pickId);

    Page<PickLineJpaEntity> findByPickId(UUID pickId, Pageable pageable);

    /** The holds among {@code reservationIds} whose pick line is SHORT (one live line per hold, uk_pick_line_reservation_live). */
    @Query("select l.reservationId from PickLineJpaEntity l where l.reservationId in :reservationIds"
            + " and l.status = com.stockflow.fulfillment.internal.domain.PickLineStatus.SHORT")
    List<UUID> findShortReservationIds(@Param("reservationIds") java.util.Collection<UUID> reservationIds);
}
