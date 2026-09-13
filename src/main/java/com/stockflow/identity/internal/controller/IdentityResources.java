package com.stockflow.identity.internal.controller;

/**
 * Permission resource codes owned by the identity module.
 *
 * <p>Each code appears exactly twice — once on the {@code @PermissionResource} that declares it,
 * once per {@code @RequiresPermission} that guards an endpoint — matching the codes already seeded
 * into {@code identity.permission} by {@code V20260903000100__identity_seed_roles_permissions.sql}.
 * If a resource's action set changes here, update that migration's catalog too; nothing validates
 * the two against each other (see that migration's own header comment).</p>
 */
public final class IdentityResources {

    public static final String ROLES = "identity-roles";
    public static final String USERS = "identity-users";
    public static final String RBAC = "identity-rbac";

    private IdentityResources() {
    }
}
