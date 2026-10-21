package com.stockflow.fulfillment.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.fulfillment.internal.entity.ShipmentEventJpaEntity;

/** The append-only shipment timeline; rows are only ever inserted. */
public interface ShipmentEventJpaRepository extends BaseJpaRepository<ShipmentEventJpaEntity> {
}
