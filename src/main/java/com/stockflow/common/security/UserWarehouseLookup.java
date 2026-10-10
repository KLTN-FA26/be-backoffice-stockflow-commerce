package com.stockflow.common.security;

import java.util.UUID;

/**
 * Answers "which warehouses is this user assigned to", on the server, per request (ADR-0008, the
 * same reasoning as {@link RoleAuthorizationLookup}): an assignment changed by an administrator
 * applies on the user's next request, with no token to outlive it.
 *
 * <p>Implemented by the identity module. Asked only for callers whose resolved scope is
 * {@link DataScope#WAREHOUSE}; everyone else is not limited to warehouses.</p>
 */
public interface UserWarehouseLookup {

    /**
     * @throws RuntimeException when the answer cannot be read; the caller must then refuse the
     *         request rather than guess
     */
    AssignedWarehouses warehousesOf(UUID userId);
}
