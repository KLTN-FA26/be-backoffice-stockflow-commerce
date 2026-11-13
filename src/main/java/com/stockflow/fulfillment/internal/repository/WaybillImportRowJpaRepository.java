package com.stockflow.fulfillment.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.fulfillment.internal.entity.WaybillImportRowJpaEntity;

import java.util.List;
import java.util.UUID;

/** The lines of imported waybill files (3-3). */
public interface WaybillImportRowJpaRepository extends BaseJpaRepository<WaybillImportRowJpaEntity> {

    List<WaybillImportRowJpaEntity> findByImportIdOrderByLineNoAsc(UUID importId);
}
