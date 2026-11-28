package com.stockflow.order.internal.controller;

import com.stockflow.common.security.Action;
import com.stockflow.common.security.PermissionResource;

/**
 * Declares {@code sales-contract-terms}, guarding {@code POST /orders/{orderId}/contract/terms-review/approval} and
 * {@code .../rejection} on {@link SalesContractController}, which already carries the {@code sales-contracts} resource
 * (QĐ-4 of the contract e-signing plan, 2026-11-28).
 *
 * <p>A marker, not a bean, as {@link QuotePriceApprovalsResourceDeclaration}: {@code PermissionCatalogValidator} scans the
 * classpath for {@code @PermissionResource}.</p>
 */
@PermissionResource(
        code = OrderResources.SALES_CONTRACT_TERMS,
        group = "Sales",
        label = "Contract terms approvals",
        route = "/orders",
        apiPath = "/api/v1/orders/{orderId}/contract/terms-review",
        actions = {Action.APPROVE})
final class ContractTermsResourceDeclaration {

    private ContractTermsResourceDeclaration() {
    }
}
