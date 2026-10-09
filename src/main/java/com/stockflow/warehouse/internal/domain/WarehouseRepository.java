package com.stockflow.warehouse.internal.domain;

import com.stockflow.common.domain.AggregateRepository;

import java.util.Optional;
import java.util.UUID;

/** Persistence port for {@link Warehouse}. */
public interface WarehouseRepository extends AggregateRepository<Warehouse, UUID> {

    /**
     * Loads the warehouse under a row write lock, held until the transaction ends. Every change to
     * the layout of one warehouse takes it first, which is what makes BR-06/BR-07 - rules across
     * several aggregates that no database constraint can state - safe under concurrency
     * (issue #18 D4).
     */
    Optional<Warehouse> findByIdForUpdate(UUID id);

    boolean existsByPrefix(String prefix);

    /** How far the layout of the warehouse reaches; see {@link MapExtent}. */
    MapExtent findOccupiedExtent(UUID warehouseId);
}
