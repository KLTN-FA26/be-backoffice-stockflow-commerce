package com.stockflow.common.security;

import java.util.List;

/**
 * Exactly what the permission admin screen needs to render one role, in one response.
 *
 * <p>Shaped after the screen itself rather than after the tables: tabs, then resource cards, then
 * action chips, with the counters precomputed. Sending the raw join and letting the frontend group
 * and count means every client re-implements the same logic and they drift.</p>
 */
public record RoleMatrixView(
        String roleCode,
        String roleLabel,
        boolean systemRole,
        DataScope dataScope,
        int grantedCount,
        int totalCount,
        List<Group> groups
) {

    /** One tab: "Payroll", "Warehouse", ... */
    public record Group(String name, int grantedCount, int totalCount, List<Resource> resources) {
    }

    /** One card: a screen with its route, its API path and its action chips. */
    public record Resource(
            String code,
            String label,
            String route,
            String apiPath,
            int grantedCount,
            int totalCount,
            List<ActionChip> actions
    ) {
    }

    /**
     * One chip.
     *
     * @param sensitive DELETE / APPROVE / EXPORT - rendered with a lock and skipped by "select all"
     */
    public record ActionChip(Action action, String label, boolean granted, boolean sensitive) {
    }
}
