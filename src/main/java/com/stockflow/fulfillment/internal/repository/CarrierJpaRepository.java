package com.stockflow.fulfillment.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.fulfillment.internal.entity.CarrierJpaEntity;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/** The carrier catalogue (G4-2a). */
public interface CarrierJpaRepository extends BaseJpaRepository<CarrierJpaEntity> {

    Optional<CarrierJpaEntity> findByCode(String code);

    /**
     * The carrier, row-locked {@code FOR NO KEY UPDATE}: writers of its settings queue up, while a load or shipment
     * naming it - a foreign key, which takes only {@code KEY SHARE} - goes on.
     */
    @Query(value = "SELECT * FROM fulfillment.carrier WHERE id = :id FOR NO KEY UPDATE", nativeQuery = true)
    Optional<CarrierJpaEntity> lockById(@Param("id") UUID id);
}
