package com.stockflow.warehouse.internal.domain;

import com.stockflow.common.domain.AggregateRepository;

import java.util.Optional;
import java.util.UUID;

/** Persistence port for {@link Boundary}. */
public interface BoundaryRepository extends AggregateRepository<Boundary, UUID> {

    /** See {@link AreaRepository#findWarehouseIdOf}; a boundary never changes warehouse either. */
    Optional<UUID> findWarehouseIdOf(UUID boundaryId);

    /** A hard delete (issue #18 D11): nothing points at a boundary. */
    void deleteById(UUID boundaryId);
}
