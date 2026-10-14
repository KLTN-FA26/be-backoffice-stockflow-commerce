package com.stockflow.fulfillment.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.fulfillment.internal.entity.PickLineJpaEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

/** Persistence access for the lines of a pick task. Always read for one task, in walking order. */
public interface PickLineJpaRepository extends BaseJpaRepository<PickLineJpaEntity> {

    List<PickLineJpaEntity> findByPickIdOrderBySequenceNoAscIdAsc(UUID pickId);

    Page<PickLineJpaEntity> findByPickId(UUID pickId, Pageable pageable);
}
