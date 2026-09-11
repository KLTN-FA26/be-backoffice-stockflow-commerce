package com.stockflow.common.security;

import java.util.Set;
import java.util.UUID;

/**
 * The authenticated caller, in domain terms rather than in Spring Security terms.
 *
 * <p>Application code should depend on this record, not on {@code Jwt} or
 * {@code SecurityContextHolder} - otherwise the security library leaks into every use case.</p>
 *
 * @param scope how far this user can see. Permissions say which actions are allowed; scope says
 *              on which rows. The query layer must apply it - see {@link DataScope}.
 */
public record CurrentUser(
        UUID userId,
        String username,
        Set<Role> roles,
        Set<PermissionCode> permissions,
        DataScope scope,
        Set<String> warehouseCodes
) {

    public boolean hasRole(Role role) {
        return roles.contains(role);
    }

    public boolean hasAnyRole(Role... candidates) {
        for (Role candidate : candidates) {
            if (roles.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    public boolean can(String resource, Action action) {
        return permissions.contains(PermissionCode.of(resource, action));
    }

    /** True when the user may see rows outside their own records. */
    public boolean seesBeyondOwnData() {
        return scope.isBroaderThan(DataScope.OWN);
    }
}
