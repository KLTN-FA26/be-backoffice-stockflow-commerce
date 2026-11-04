package com.stockflow.production.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.production.internal.entity.SampleRequestJpaEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface SampleRequestJpaRepository extends BaseJpaRepository<SampleRequestJpaEntity> {

    @Query("select s.id from SampleRequestJpaEntity s where s.previousRoundId = :id")
    Optional<UUID> findNextRoundId(@Param("id") UUID id);

    /** Served by {@code ix_sample_request_approved}, the partial index on APPROVED rows. */
    @Query("""
            select s from SampleRequestJpaEntity s
             where s.designSnapshotId = :design and s.blankSku = :blank
               and s.status = com.stockflow.production.internal.domain.SampleStatus.APPROVED
             order by s.respondedAt desc""")
    List<SampleRequestJpaEntity> findApproved(@Param("design") UUID designSnapshotId, @Param("blank") String blankSku,
                                              Pageable limit);
}
