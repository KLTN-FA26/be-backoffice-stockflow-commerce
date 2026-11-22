package com.stockflow.common.security;

import java.util.Set;

/**
 * What a set of roles grants, as answered by {@link RoleAuthorizationLookup}.
 *
 * @param scope how far the caller sees: the broadest {@code app_role.data_scope} of their roles
 *              (SCRUM-457; WAREHOUSE_STAFF and WAREHOUSE_MANAGER are WAREHOUSE, the others ALL), and
 *              the narrowest scope for someone holding none.
 */
public record ResolvedAuthorization(Set<PermissionCode> permissions, DataScope scope) {

    public ResolvedAuthorization {
        permissions = Set.copyOf(permissions);
        if (scope == null) {
            throw new IllegalArgumentException("scope must not be null");
        }
    }

    /** Nothing granted, narrowest scope: a caller with no known role. */
    public static ResolvedAuthorization none() {
        return new ResolvedAuthorization(Set.of(), DataScope.OWN);
    }
}
