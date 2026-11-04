package com.stockflow.fulfillment.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.fulfillment.internal.entity.CarrierJpaEntity;

import java.util.Optional;

/** The carrier catalogue (G4-2a). */
public interface CarrierJpaRepository extends BaseJpaRepository<CarrierJpaEntity> {

    Optional<CarrierJpaEntity> findByCode(String code);
}
