package com.stockflow.common.security;

import java.util.Set;
import java.util.UUID;

/**
 * The warehouses a staff member is assigned to (BR-SEC-002), in both shapes the modules need:
 * ids for documents that name a warehouse (transfer orders, goods receipts) and prefixes for
 * location codes, which start with their warehouse's prefix ({@code HCM-A01-2-03}).
 */
public record AssignedWarehouses(Set<UUID> ids, Set<String> prefixes) {

    public AssignedWarehouses {
        ids = ids == null ? Set.of() : Set.copyOf(ids);
        prefixes = prefixes == null ? Set.of() : Set.copyOf(prefixes);
    }

    public static AssignedWarehouses none() {
        return new AssignedWarehouses(Set.of(), Set.of());
    }
}
