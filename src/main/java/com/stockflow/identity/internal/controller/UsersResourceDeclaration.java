package com.stockflow.identity.internal.controller;

import com.stockflow.common.security.Action;
import com.stockflow.common.security.PermissionResource;

/**
 * Declares the "users" permission resource. Separate from {@link IdentityController}'s own
 * {@code @PermissionResource} for the same reason {@code ReservationsResourceDeclaration} exists in
 * {@code inventory}: a type-level annotation can only appear once per class, and this controller
 * serves more than one distinct resource (roles, users, the permission matrix).
 *
 * <p>Not a Spring bean — {@code PermissionCatalogValidator} scans the classpath for
 * {@code @PermissionResource}, not the bean registry, so an empty marker class is enough.</p>
 */
@PermissionResource(
        code = IdentityResources.USERS,
        group = "Platform",
        label = "Users",
        route = "/admin/users",
        apiPath = "/api/v1/identity/users",
        actions = {Action.VIEW_PAGE, Action.READ, Action.CREATE, Action.UPDATE})
final class UsersResourceDeclaration {

    private UsersResourceDeclaration() {
    }
}
