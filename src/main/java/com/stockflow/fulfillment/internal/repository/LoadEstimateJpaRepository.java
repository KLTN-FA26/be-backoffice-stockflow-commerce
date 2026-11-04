package com.stockflow.fulfillment.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.fulfillment.internal.entity.LoadEstimateJpaEntity;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Load estimates (SCRUM-287 PR EST): at most one CURRENT per source ({@code uk_load_estimate_current}). */
public interface LoadEstimateJpaRepository extends BaseJpaRepository<LoadEstimateJpaEntity> {

    Optional<LoadEstimateJpaEntity> findBySourceTypeAndSourceIdAndStatus(String sourceType, UUID sourceId,
                                                                          String status);

    List<LoadEstimateJpaEntity> findAllBySourceTypeAndSourceIdAndStatus(String sourceType, UUID sourceId,
                                                                        String status);

    /**
     * Serialises the transactions that store an estimate of this source until they end, so two coordinators
     * estimating the same order at once both succeed, one after the other, the later superseding the earlier - instead
     * of the second insert failing on the one-current index. Wrapped in a sub-select because
     * {@code pg_advisory_xact_lock} returns {@code void}.
     */
    @Query(value = "SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))) AS locked",
            nativeQuery = true)
    Integer lockSource(@Param("key") String key);
}
