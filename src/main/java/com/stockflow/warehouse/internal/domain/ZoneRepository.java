package com.stockflow.warehouse.internal.domain;

import com.stockflow.common.domain.AggregateRepository;

import java.util.List;
import java.util.UUID;

/** Persistence port for {@link Zone}. */
public interface ZoneRepository extends AggregateRepository<Zone, UUID> {

    /** Ordered by name; a warehouse has a handful of zones, so no paging. */
    List<Zone> findByWarehouseId(UUID warehouseId);
}
