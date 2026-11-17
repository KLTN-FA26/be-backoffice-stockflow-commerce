package com.stockflow.warehouse.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.warehouse.internal.entity.PutawayTripItemJpaEntity;

import java.util.List;
import java.util.UUID;

/** Spring Data repository for {@link PutawayTripItemJpaEntity}. */
interface PutawayTripItemJpaRepository extends BaseJpaRepository<PutawayTripItemJpaEntity> {

    /** In the order the cartons came onto the cart (ids are time-ordered). */
    List<PutawayTripItemJpaEntity> findByTripIdOrderByIdAsc(UUID tripId);
}
