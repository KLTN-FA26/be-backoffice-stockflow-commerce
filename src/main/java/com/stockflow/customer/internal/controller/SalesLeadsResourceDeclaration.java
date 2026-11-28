package com.stockflow.customer.internal.controller;

import com.stockflow.common.security.Action;
import com.stockflow.common.security.PermissionResource;

/**
 * Catalog declaration of {@code sales-leads} (V20270128000140; sales-owner plan §10.7): businesses that asked to open an
 * account. Sales see and work their own; a manager sees all and assigns (checked in the service).
 */
@PermissionResource(code = CustomerResources.SALES_LEADS, group = "Customer", label = "Leads",
        route = "/admin/sales/leads", apiPath = "/api/v1/leads", actions = {Action.READ, Action.UPDATE})
final class SalesLeadsResourceDeclaration {
    private SalesLeadsResourceDeclaration() { }
}
