package com.stockflow.common.security;

import java.util.Set;

/**
 * What a set of roles grants, as answered by {@link RoleAuthorizationLookup}.
 *
 * @param scope how far the caller sees. No role carries a scope of its own yet (ADR-0004 records the
 *              row filter as unimplemented), so this is {@link DataScope#ALL} for anyone holding a
 *              known role — the value the {@code scope_level} claim used to carry — and the
 *              narrowest scope for someone holding none.
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
