package com.stockflow.common.security;

import java.util.List;
import java.util.Set;

/**
 * One resource card in the admin permission screen: what it is called, where it lives, and which
 * actions can be granted on it.
 */
public record PermissionCatalogEntry(
        String code,
        String group,
        String label,
        String route,
        String apiPath,
        List<Action> actions
) {

    public Set<PermissionCode> permissions() {
        return actions.stream().map(a -> PermissionCode.of(code, a))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public boolean supports(Action action) {
        return actions.contains(action);
    }
}
