package com.stockflow.common.security;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns "the catalog" plus "what this role was granted" into the screen's view model.
 *
 * <p>Pure function, no Spring, no database: given the same catalog and the same grants it always
 * produces the same matrix, which makes it trivial to test and impossible for the counters to
 * disagree with the chips.</p>
 */
public final class RoleMatrixAssembler {

    private RoleMatrixAssembler() {
    }

    public static RoleMatrixView assemble(String roleCode,
                                          String roleLabel,
                                          boolean systemRole,
                                          DataScope dataScope,
                                          PermissionCatalog catalog,
                                          Set<PermissionCode> granted) {

        List<RoleMatrixView.Group> groups = new ArrayList<>();
        int totalGranted = 0;
        int totalAll = 0;

        for (Map.Entry<String, List<PermissionCatalogEntry>> tab : catalog.byGroup().entrySet()) {
            List<RoleMatrixView.Resource> resources = new ArrayList<>();
            int groupGranted = 0;
            int groupTotal = 0;

            for (PermissionCatalogEntry entry : tab.getValue()) {
                List<RoleMatrixView.ActionChip> chips = new ArrayList<>();
                int resourceGranted = 0;

                for (Action action : entry.actions()) {
                    boolean isGranted = granted.contains(PermissionCode.of(entry.code(), action));
                    if (isGranted) {
                        resourceGranted++;
                    }
                    chips.add(new RoleMatrixView.ActionChip(
                            action, action.label(), isGranted, action.isSensitive()));
                }

                resources.add(new RoleMatrixView.Resource(
                        entry.code(), entry.label(), entry.route(), entry.apiPath(),
                        resourceGranted, entry.actions().size(), chips));

                groupGranted += resourceGranted;
                groupTotal += entry.actions().size();
            }

            groups.add(new RoleMatrixView.Group(tab.getKey(), groupGranted, groupTotal, resources));
            totalGranted += groupGranted;
            totalAll += groupTotal;
        }

        return new RoleMatrixView(roleCode, roleLabel, systemRole, dataScope,
                totalGranted, totalAll, groups);
    }

    /**
     * What "select all" on a tab grants: everything except the sensitive actions.
     *
     * <p>DELETE, APPROVE and EXPORT stay unticked on purpose. An administrator clicking one button
     * should not silently hand out the ability to wipe a table or export the salary of every
     * employee - those three deserve a separate, conscious click.</p>
     */
    public static Set<PermissionCode> selectAllSafely(PermissionCatalog catalog, String group) {
        Set<PermissionCode> result = new java.util.LinkedHashSet<>();
        for (PermissionCatalogEntry entry : catalog.byGroup().getOrDefault(group, List.of())) {
            for (Action action : entry.actions()) {
                if (!action.isSensitive()) {
                    result.add(PermissionCode.of(entry.code(), action));
                }
            }
        }
        return result;
    }
}
