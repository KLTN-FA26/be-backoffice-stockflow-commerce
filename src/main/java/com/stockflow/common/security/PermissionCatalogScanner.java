package com.stockflow.common.security;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Builds the permission catalog from annotations, and refuses to hand one back when the code and
 * the catalog disagree.
 *
 * <p>Deliberately free of Spring. The rules here are the interesting part and they are worth being
 * able to unit test in milliseconds, without a context; the Spring layer above only supplies the
 * list of classes.</p>
 */
public final class PermissionCatalogScanner {

    private PermissionCatalogScanner() {
    }

    /**
     * @param candidates classes to inspect - controllers in production, a handful of fixtures in a test
     * @throws IllegalStateException if an endpoint requires a permission the catalog cannot grant
     */
    public static PermissionCatalog scan(Collection<Class<?>> candidates) {
        List<PermissionCatalogEntry> entries = new ArrayList<>();
        for (Class<?> type : candidates) {
            PermissionResource annotation = type.getAnnotation(PermissionResource.class);
            if (annotation != null) {
                entries.add(new PermissionCatalogEntry(
                        annotation.code(), annotation.group(), annotation.label(),
                        annotation.route(), annotation.apiPath(), List.of(annotation.actions())));
            }
        }

        PermissionCatalog catalog = new PermissionCatalog(entries);
        List<String> problems = findUngrantableGuards(candidates, catalog);
        if (!problems.isEmpty()) {
            throw new IllegalStateException(
                    "Permission catalog is out of sync with the code:" + System.lineSeparator()
                            + "  - " + String.join(System.lineSeparator() + "  - ", problems));
        }
        return catalog;
    }

    /**
     * Finds endpoints guarded by a permission no administrator could ever tick.
     *
     * <p>Two shapes of the same bug, both silent in a database-only permission model: a resource
     * that was never declared, and an action the resource does not list. Either way the endpoint is
     * permanently unreachable and the only symptom is a 403 that looks like a configuration
     * mistake.</p>
     */
    public static List<String> findUngrantableGuards(Collection<Class<?>> candidates,
                                                     PermissionCatalog catalog) {
        List<String> problems = new ArrayList<>();
        for (Class<?> type : candidates) {
            for (Method method : type.getDeclaredMethods()) {
                RequiresPermission required = method.getAnnotation(RequiresPermission.class);
                if (required == null) {
                    continue;
                }
                PermissionCode code = PermissionCode.of(required.resource(), required.action());
                if (catalog.find(code.resource()).isEmpty()) {
                    problems.add("%s#%s requires %s, but no @PermissionResource declares '%s'"
                            .formatted(type.getSimpleName(), method.getName(), code, code.resource()));
                } else if (!catalog.isDeclared(code)) {
                    problems.add("%s#%s requires %s, but resource '%s' does not list action %s"
                            .formatted(type.getSimpleName(), method.getName(), code,
                                    code.resource(), code.action()));
                }
            }
        }
        return problems;
    }
}
