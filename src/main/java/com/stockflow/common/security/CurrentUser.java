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

    /**
     * Whether the caller may act as {@code role}. {@link Role#SYSTEM_ADMIN} may act as any role:
     * code across several modules still gates actions on role names rather than on permissions, and
     * the role that holds every permission must not be turned away by those checks. The same rule
     * reaches {@code @PreAuthorize} and URL rules through {@link RoleHierarchyConfig}.
     *
     * <p>This answers "may the caller do what a holder of this role may do", never "does the caller
     * belong to this role" for data purposes: nothing may use it to narrow a query to the caller's
     * own rows, or the administrator would be narrowed with everyone else.</p>
     */
    public boolean hasRole(Role role) {
        return roles.contains(role) || roles.contains(Role.SYSTEM_ADMIN);
    }

    public boolean hasAnyRole(Role... candidates) {
        if (roles.contains(Role.SYSTEM_ADMIN)) {
            return true;
        }
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
