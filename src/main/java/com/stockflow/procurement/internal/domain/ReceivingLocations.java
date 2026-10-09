package com.stockflow.procurement.internal.domain;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Read port over the warehouse map, for receiving; see {@link ReceivingLocation}. */
public interface ReceivingLocations {

    Optional<ReceivingLocation> byCode(String code);

    Map<UUID, ReceivingLocation> byIds(Collection<UUID> ids);

    /** Whether the warehouse has at least one active area of this type. */
    boolean hasArea(UUID warehouseId, String areaType);
}
