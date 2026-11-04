package com.stockflow.fulfillment.internal.controller;

import com.stockflow.common.security.Action;
import com.stockflow.common.security.PermissionResource;

/** Catalog declaration kept separate because one controller serves two permission resources. */
@PermissionResource(code = FulfillmentResources.PACKAGES, group = "Fulfillment", label = "Packages",
        route = "/fulfillment/packages", apiPath = "/api/v1/fulfillment/tasks",
        actions = {Action.VIEW_PAGE, Action.READ, Action.CREATE})
final class FulfillmentPackageResourceDeclaration {
    private FulfillmentPackageResourceDeclaration() { }
}
