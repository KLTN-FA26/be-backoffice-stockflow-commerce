package com.stockflow.identity.internal.controller;

import com.stockflow.common.security.Action;
import com.stockflow.common.security.PermissionResource;

/**
 * Catalog declaration of {@code staff-covers} (V20270128000140; sales-owner plan BR-S07): who stands in for an absent
 * salesperson. A salesperson arranges and cancels covers of their own absence; a manager, anyone's (checked in
 * {@code StaffCoverService}).
 */
@PermissionResource(code = IdentityResources.STAFF_COVERS, group = "Platform", label = "Covers during leave",
        route = "/admin/sales/covers", apiPath = "/api/v1/staff-covers", actions = {Action.READ, Action.CREATE, Action.UPDATE})
final class StaffCoversResourceDeclaration {
    private StaffCoversResourceDeclaration() { }
}
