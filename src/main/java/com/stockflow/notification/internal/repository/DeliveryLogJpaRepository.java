package com.stockflow.notification.internal.repository;

import com.stockflow.notification.internal.entity.DeliveryLogJpaEntity;
import com.stockflow.common.persistence.BaseJpaRepository;

/** Spring Data repository for {@link DeliveryLogJpaEntity}. STARTER STUB. */
public interface DeliveryLogJpaRepository extends BaseJpaRepository<DeliveryLogJpaEntity> {
    /** Transaction-scoped serialization also covers the first attempt, before a log row exists. */
    @org.springframework.data.jpa.repository.Query(value = "select pg_try_advisory_xact_lock(hashtextextended(:reference, 0))", nativeQuery = true)
    boolean tryLockDelivery(String reference);

    boolean existsByExternalReference(String externalReference);
    org.springframework.data.domain.Page<DeliveryLogJpaEntity> findByOperationReference(
            String reference, org.springframework.data.domain.Pageable pageable);
}
