package com.stockflow.fulfillment.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.fulfillment.internal.entity.DeliveryOutcomeImportRowJpaEntity;

import java.util.List;
import java.util.UUID;

/** The lines of delivery-outcome files (WF-05 fix plan item 5). */
public interface DeliveryOutcomeImportRowJpaRepository extends BaseJpaRepository<DeliveryOutcomeImportRowJpaEntity> {

    List<DeliveryOutcomeImportRowJpaEntity> findByImportIdOrderByLineNoAsc(UUID importId);
}
