package com.stockflow.warehouse.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.warehouse.internal.domain.PutawayTripStatus;
import com.stockflow.warehouse.internal.entity.PutawayTripJpaEntity;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/** Spring Data repository for {@link PutawayTripJpaEntity}. */
interface PutawayTripJpaRepository extends BaseJpaRepository<PutawayTripJpaEntity> {

    Optional<PutawayTripJpaEntity> findFirstByOperatorIdAndStatusIn(UUID operatorId,
                                                                   Collection<PutawayTripStatus> statuses);
}
