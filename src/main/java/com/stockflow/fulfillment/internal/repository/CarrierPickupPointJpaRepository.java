package com.stockflow.fulfillment.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.fulfillment.internal.entity.CarrierPickupPointJpaEntity;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/** Where API_BOOKING carriers pick up at each warehouse (3-4). */
public interface CarrierPickupPointJpaRepository extends BaseJpaRepository<CarrierPickupPointJpaEntity> {

    List<CarrierPickupPointJpaEntity> findByCarrierIdOrderByWarehouseIdAsc(UUID carrierId);

    /** Flushed and cleared before the replacement rows go in, so the unique key never sees old and new together. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from CarrierPickupPointJpaEntity p where p.carrierId = :carrierId")
    int deleteByCarrier(@Param("carrierId") UUID carrierId);
}
