package com.stockflow.fulfillment.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.fulfillment.internal.entity.DeliveryOutcomeImportJpaEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

/** Files of delivery outcomes applied (WF-05 fix plan item 5). */
public interface DeliveryOutcomeImportJpaRepository extends BaseJpaRepository<DeliveryOutcomeImportJpaEntity> {

    /** The imports one person made; the page says the order. */
    Page<DeliveryOutcomeImportJpaEntity> findByUploadedBy(UUID uploadedBy, Pageable page);
}
