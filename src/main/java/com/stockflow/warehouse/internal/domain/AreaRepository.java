package com.stockflow.warehouse.internal.domain;

import com.stockflow.common.domain.AggregateRepository;

import java.util.Optional;
import java.util.UUID;

/** Persistence port for {@link Area}: the area and its storage location, loaded and saved together. */
public interface AreaRepository extends AggregateRepository<Area, UUID> {

    /**
     * The warehouse an area belongs to, without loading the area, so a writer can take the
     * warehouse lock before reading it. Safe unlocked: an area never changes warehouse
     * ({@code tg_area_immutable}).
     */
    Optional<UUID> findWarehouseIdOf(UUID areaId);

    boolean existsByWarehouseIdAndCode(UUID warehouseId, String code);
}
