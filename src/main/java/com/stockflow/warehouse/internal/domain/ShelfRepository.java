package com.stockflow.warehouse.internal.domain;

import com.stockflow.common.domain.AggregateRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Persistence port for {@link Shelf}: the whole tree - levels, bins, storage locations - is loaded
 * and saved together, in a number of queries that does not grow with the number of bins.
 */
public interface ShelfRepository extends AggregateRepository<Shelf, UUID> {

    /**
     * The warehouse a shelf belongs to, without loading the shelf. A writer needs it to take the
     * warehouse lock <i>before</i> reading the tree; reading it unlocked is safe because a shelf
     * never changes warehouse ({@code tg_shelf_immutable}).
     */
    Optional<UUID> findWarehouseIdOf(UUID shelfId);

    boolean existsByWarehouseIdAndCode(UUID warehouseId, String code);
}
