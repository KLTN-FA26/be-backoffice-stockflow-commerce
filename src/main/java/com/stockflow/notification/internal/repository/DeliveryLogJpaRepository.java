package com.stockflow.notification.internal.repository;

import com.stockflow.notification.internal.entity.DeliveryLogJpaEntity;
import com.stockflow.common.persistence.BaseJpaRepository;

/** Spring Data repository for {@link DeliveryLogJpaEntity}. STARTER STUB. */
public interface DeliveryLogJpaRepository extends BaseJpaRepository<DeliveryLogJpaEntity> {

    boolean existsByExternalReference(String externalReference);
}
