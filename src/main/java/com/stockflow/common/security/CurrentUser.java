package com.stockflow.common.security;

import java.util.Set;
import java.util.UUID;

/**
 * The authenticated caller, in domain terms rather than in Spring Security terms.
 *
 * <p>Application code should depend on this record, not on {@code Jwt} or
 * {@code SecurityContextHolder} - otherwise the security library leaks into every use case.</p>
 *
 * @param scope          how far this user can see. Permissions say which actions are allowed; scope
 *                       says on which rows. The query layer must apply it - see {@link DataScope}.
 * @param warehouseCodes prefixes of the warehouses the user is assigned to (BR-SEC-002)
 * @param warehouseIds   ids of the same warehouses
 */
public record CurrentUser(
        UUID userId,
        String username,
        Set<Role> roles,
        Set<PermissionCode> permissions,
        DataScope scope,
        Set<String> warehouseCodes,
        Set<java.util.UUID> warehouseIds
) {

    /**
     * Without warehouse ids: callers that limit by location code only, and every caller written
     * before ids were carried (SCRUM-457).
     */
    public CurrentUser(UUID userId, String username, Set<Role> roles, Set<PermissionCode> permissions,
                       DataScope scope, Set<String> warehouseCodes) {
        this(userId, username, roles, permissions, scope, warehouseCodes, Set.of());
    }

    /**
     * Whether the caller holds {@code resource:action}. Access is always asked this way, never by role name: the
     * grants are data (ADR-0008), so ticking a permission for another role widens the rule without a code change.
     */
    public boolean can(String resource, Action action) {
        return permissions.contains(PermissionCode.of(resource, action));
    }

    /** {@link #can}, or 403 {@link PermissionDeniedException} - for checks a service makes beyond the endpoint's. */
    public void require(String resource, Action action) {
        if (!can(resource, action)) {
            throw new PermissionDeniedException(PermissionCode.of(resource, action));
        }
    }

    /**
     * True for a storefront (portal) account: one whose every role is {@link Role#CUSTOMER}. Such an account holds some
     * of the same permissions as staff - {@code sales-orders:READ} for its own orders - so this answers WHICH rows
     * (its own customer's), never WHETHER an action is allowed; that is the permission's job. See {@link ScopeGuards}.
     */
    public boolean isPortalCustomer() {
        return !roles.isEmpty() && roles.stream().allMatch(role -> role == Role.CUSTOMER);
    }

    /** True when the user may see rows outside their own records. */
    public boolean seesBeyondOwnData() {
        return scope.isBroaderThan(DataScope.OWN);
    }
}
