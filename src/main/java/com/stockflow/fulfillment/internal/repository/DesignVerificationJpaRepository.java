package com.stockflow.fulfillment.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.fulfillment.internal.entity.DesignVerificationJpaEntity;
import java.util.List;
import java.util.UUID;

public interface DesignVerificationJpaRepository extends BaseJpaRepository<DesignVerificationJpaEntity> {
    List<DesignVerificationJpaEntity> findByPackIdOrderByVerifiedAtDesc(UUID packId);
}
