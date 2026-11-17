package com.stockflow.design.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.design.internal.entity.DesignReviewRoundJpaEntity;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data repository for {@link DesignReviewRoundJpaEntity}: projects' review rounds. */
public interface DesignReviewRoundJpaRepository extends BaseJpaRepository<DesignReviewRoundJpaEntity> {

    /** The round, locked: every decision on one of its designs is counted under it (BR-20). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from DesignReviewRoundJpaEntity r where r.id = :id")
    Optional<DesignReviewRoundJpaEntity> lockById(@Param("id") UUID id);

    /** The Sales Manager's queue of rounds, the longest waiting first (ix_design_review_round_open). */
    @Query(value = "select r from DesignReviewRoundJpaEntity r where r.closedAt is null "
            + "order by r.submittedAt asc, r.id asc",
            countQuery = "select count(r) from DesignReviewRoundJpaEntity r where r.closedAt is null")
    Page<DesignReviewRoundJpaEntity> open(Pageable page);
}
