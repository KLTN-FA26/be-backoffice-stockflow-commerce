package com.stockflow.inventory.internal.controller;

import com.stockflow.identity.api.IdentityService;
import com.stockflow.warehouse.api.WarehouseService;
import com.stockflow.warehouse.api.WarehouseView;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * The readable labels a response carries beside the ids it already has: a person's display name beside a user id,
 * a warehouse's code and name beside a warehouse id. Without them a screen shows a UUID, or has to call the user and
 * warehouse lists - which a warehouse clerk may not be allowed to read - just to name what it shows.
 *
 * <p>Each lookup is one query for a whole response or page: gather every id first, then ask once. Asking per row
 * would turn a list endpoint into an N+1. A label is null when its id is null or unknown; it is a label, never a
 * reason to fail the response.</p>
 */
@Component
class ResponseLabels {

    private final IdentityService identities;
    private final WarehouseService warehouses;

    ResponseLabels(IdentityService identities, WarehouseService warehouses) {
        this.identities = identities;
        this.warehouses = warehouses;
    }

    /** Display names (full name, else username) of these users, by id; nulls and repeats are ignored. */
    public Map<UUID, String> names(Stream<UUID> userIds) {
        List<UUID> ids = userIds.filter(Objects::nonNull).distinct().toList();
        return ids.isEmpty() ? Map.of() : identities.displayNames(ids);
    }

    /** These warehouses, by id; nulls and repeats are ignored, an unknown id is absent. */
    public Map<UUID, WarehouseView> warehouses(Stream<UUID> warehouseIds) {
        List<UUID> ids = warehouseIds.filter(Objects::nonNull).distinct().toList();
        return ids.isEmpty() ? Map.of() : warehouses.findWarehouses(ids);
    }

    static String name(Map<UUID, String> names, UUID userId) {
        return userId == null ? null : names.get(userId);
    }

    static String code(Map<UUID, WarehouseView> found, UUID warehouseId) {
        WarehouseView warehouse = warehouseId == null ? null : found.get(warehouseId);
        return warehouse == null ? null : warehouse.prefix();
    }

    static String warehouseName(Map<UUID, WarehouseView> found, UUID warehouseId) {
        WarehouseView warehouse = warehouseId == null ? null : found.get(warehouseId);
        return warehouse == null ? null : warehouse.name();
    }
}
