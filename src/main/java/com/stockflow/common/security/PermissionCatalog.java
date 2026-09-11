package com.stockflow.common.security;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Everything this service can be asked permission for, built at startup from the
 * {@link PermissionResource} annotations rather than from a seed script.
 *
 * <p>identity-service collects one of these from every service and assembles the full matrix that
 * the admin screen renders. That is what makes "382 permissions" a generated number instead of a
 * hand-maintained list somebody has to remember to update.</p>
 */
public class PermissionCatalog {

    private final Map<String, PermissionCatalogEntry> byCode;

    public PermissionCatalog(Collection<PermissionCatalogEntry> entries) {
        Map<String, PermissionCatalogEntry> map = new LinkedHashMap<>();
        for (PermissionCatalogEntry entry : entries) {
            PermissionCatalogEntry clash = map.putIfAbsent(entry.code(), entry);
            if (clash != null) {
                throw new IllegalStateException(
                        "Duplicate permission resource code '" + entry.code() + "'. Codes are the "
                                + "primary key of the matrix and must be unique across all services.");
            }
        }
        this.byCode = Map.copyOf(map);
    }

    public Optional<PermissionCatalogEntry> find(String code) {
        return Optional.ofNullable(byCode.get(code));
    }

    public Collection<PermissionCatalogEntry> entries() {
        return byCode.values();
    }

    public Set<PermissionCode> allPermissions() {
        return byCode.values().stream()
                .flatMap(e -> e.permissions().stream())
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Entries grouped by tab, the shape the admin screen needs. */
    public Map<String, java.util.List<PermissionCatalogEntry>> byGroup() {
        return byCode.values().stream()
                .collect(Collectors.groupingBy(PermissionCatalogEntry::group,
                        LinkedHashMap::new, Collectors.toList()));
    }

    public boolean isDeclared(PermissionCode code) {
        return find(code.resource()).map(e -> e.supports(code.action())).orElse(false);
    }
}
