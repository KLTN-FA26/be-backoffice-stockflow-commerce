package com.stockflow.inventory.internal.domain;

import java.util.Optional;
import java.util.UUID;

/**
 * Warehouse id → the prefix its location codes start with ({@code HCM} in {@code HCM-A01-1-A}).
 * A count or a lot trace names its warehouse by id; stock rows name locations by code.
 *
 * <p>A port because the answer belongs to {@code warehouse}; the adapter asks {@code warehouse :: api},
 * as {@link LocationDirectory}'s does.</p>
 */
public interface WarehouseDirectory {

    /** Empty when no warehouse has this id. */
    Optional<String> prefixOf(UUID warehouseId);

    /**
     * The warehouse each of {@code locationCodes} is in, in one read (#454, a page of cartons); an unknown code is
     * absent.
     */
    java.util.Map<String, UUID> warehousesOf(java.util.Collection<String> locationCodes);
}
