package com.stockflow.payment.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.payment.internal.entity.CreditRequestFileJpaEntity;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Documents kept with credit requests. */
public interface CreditRequestFileJpaRepository extends BaseJpaRepository<CreditRequestFileJpaEntity> {

    List<CreditRequestFileJpaEntity> findByCreditRequestIdOrderByUploadedAtAscIdAsc(UUID creditRequestId);

    long countByCreditRequestId(UUID creditRequestId);

    Optional<CreditRequestFileJpaEntity> findByIdAndCreditRequestId(UUID id, UUID creditRequestId);

    Optional<CreditRequestFileJpaEntity> findByStorageKeyAndCreditRequestId(String storageKey, UUID creditRequestId);

    /** Those of {@code keys} a credit request keeps (the orphaned-file sweep). */
    @Query("select f.storageKey from CreditRequestFileJpaEntity f where f.storageKey in :keys")
    List<String> storageKeysIn(@Param("keys") Collection<String> keys);
}
