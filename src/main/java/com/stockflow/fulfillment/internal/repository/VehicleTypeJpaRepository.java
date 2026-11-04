package com.stockflow.fulfillment.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.fulfillment.internal.entity.VehicleTypeJpaEntity;

import java.util.Optional;

/** The vehicle-type catalogue (SCRUM-287). */
public interface VehicleTypeJpaRepository extends BaseJpaRepository<VehicleTypeJpaEntity> {

    Optional<VehicleTypeJpaEntity> findByCode(String code);
}
