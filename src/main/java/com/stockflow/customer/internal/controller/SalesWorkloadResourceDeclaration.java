package com.stockflow.customer.internal.controller;

import com.stockflow.common.security.Action;
import com.stockflow.common.security.PermissionResource;

/**
 * Catalog declaration of {@code sales-workload} (V20270128000140; sales-owner plan §10.4): the managers' board of each
 * salesperson's customers, conversations and documents, and their assignment settings.
 */
@PermissionResource(code = CustomerResources.SALES_WORKLOAD, group = "Customer", label = "Sales workload",
        route = "/admin/sales/assignment", apiPath = "/api/v1/sales/workload", actions = {Action.READ})
final class SalesWorkloadResourceDeclaration {
    private SalesWorkloadResourceDeclaration() { }
}
