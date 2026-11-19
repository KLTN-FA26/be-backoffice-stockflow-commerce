package com.stockflow.fulfillment.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.fulfillment.internal.entity.FailureReasonCorrectionJpaEntity;

import java.util.List;
import java.util.UUID;

/** Failure reasons corrected by hand (kltn-docs 09 edge "hoàn hàng khi còn Handed Over"). */
public interface FailureReasonCorrectionJpaRepository extends BaseJpaRepository<FailureReasonCorrectionJpaEntity> {

    List<FailureReasonCorrectionJpaEntity> findByShipmentIdOrderByCorrectedAtAscIdAsc(UUID shipmentId);
}
