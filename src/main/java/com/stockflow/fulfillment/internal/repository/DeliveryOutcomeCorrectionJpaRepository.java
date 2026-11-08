package com.stockflow.fulfillment.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.fulfillment.internal.entity.DeliveryOutcomeCorrectionJpaEntity;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Outcomes corrected by hand (3-6; kltn-docs 09 §4.3 step 5, E17). */
public interface DeliveryOutcomeCorrectionJpaRepository extends BaseJpaRepository<DeliveryOutcomeCorrectionJpaEntity> {

    List<DeliveryOutcomeCorrectionJpaEntity> findByShipmentIdOrderByCorrectedAtAscIdAsc(UUID shipmentId);

    /** The codes of those of {@code shipmentIds} whose outcome was corrected by hand (E17). */
    @Query("select distinct s.code from DeliveryOutcomeCorrectionJpaEntity c, ShipmentJpaEntity s "
            + "where s.id = c.shipmentId and c.shipmentId in :shipmentIds order by s.code")
    List<String> correctedAmong(@Param("shipmentIds") Collection<UUID> shipmentIds);
}
