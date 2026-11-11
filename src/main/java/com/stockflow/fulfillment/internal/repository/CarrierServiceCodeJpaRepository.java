package com.stockflow.fulfillment.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.fulfillment.internal.entity.CarrierServiceCodeJpaEntity;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** An API_BOOKING carrier's service codes per vehicle type and province (3-1). */
public interface CarrierServiceCodeJpaRepository extends BaseJpaRepository<CarrierServiceCodeJpaEntity> {

    List<CarrierServiceCodeJpaEntity> findByCarrierIdOrderByProvinceCodeAscServiceCodeAsc(UUID carrierId);

    Optional<CarrierServiceCodeJpaEntity> findByCarrierIdAndVehicleTypeIdAndProvinceCode(UUID carrierId,
                                                                                          UUID vehicleTypeId,
                                                                                          String provinceCode);

    /** Flushed and cleared before the replacement rows go in, so the unique key never sees old and new together. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from CarrierServiceCodeJpaEntity c where c.carrierId = :carrierId")
    int deleteByCarrier(@Param("carrierId") UUID carrierId);
}
