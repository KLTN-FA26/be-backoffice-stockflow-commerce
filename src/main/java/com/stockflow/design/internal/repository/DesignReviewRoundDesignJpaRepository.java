package com.stockflow.design.internal.repository;

import com.stockflow.design.internal.entity.DesignReviewRoundDesignJpaEntity;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link DesignReviewRoundDesignJpaEntity}. A plain {@link JpaRepository}: the row has a
 * composite key and no version, so {@code BaseJpaRepository}'s specifications have nothing to scope.
 */
public interface DesignReviewRoundDesignJpaRepository
        extends JpaRepository<DesignReviewRoundDesignJpaEntity, DesignReviewRoundDesignJpaEntity.Key> {

    /** The round a design waits in, if any (at most one: uk_design_review_round_design_waiting). */
    @Query("select m from DesignReviewRoundDesignJpaEntity m where m.draftId = :draftId and m.outcome is null")
    Optional<DesignReviewRoundDesignJpaEntity> waiting(@Param("draftId") UUID draftId);

    List<DesignReviewRoundDesignJpaEntity> findByRoundId(UUID roundId);

    List<DesignReviewRoundDesignJpaEntity> findByRoundIdIn(Collection<UUID> roundIds);
}
