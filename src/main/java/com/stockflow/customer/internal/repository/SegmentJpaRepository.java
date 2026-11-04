package com.stockflow.customer.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.customer.internal.entity.SegmentJpaEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/** Spring Data repository for {@link SegmentJpaEntity}; package-private, reached through the ports. */
interface SegmentJpaRepository extends BaseJpaRepository<SegmentJpaEntity> {

    boolean existsByCode(String code);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SegmentJpaEntity s where s.id = :id")
    Optional<SegmentJpaEntity> lockById(@Param("id") UUID id);
}
