package com.stockflow.order.internal.controller;

import com.stockflow.common.security.Action;
import com.stockflow.common.security.PermissionResource;

/**
 * Declares {@code sales-contract-signatures}: signing a contract for the company once the customer has signed (QĐ-2 of the
 * contract e-signing plan, 2026-11-28). Its endpoints ({@code /orders/{orderId}/contract/company-signature*}) belong to the
 * signing machinery; the permission is declared and granted here so the catalog and the role matrix already agree.
 *
 * <p>A marker, not a bean, as {@link ContractTermsResourceDeclaration}.</p>
 */
@PermissionResource(
        code = OrderResources.SALES_CONTRACT_SIGNATURES,
        group = "Sales",
        label = "Contract signatures (company)",
        route = "/orders",
        apiPath = "/api/v1/orders/{orderId}/contract/company-signature",
        actions = {Action.APPROVE})
final class ContractSignaturesResourceDeclaration {

    private ContractSignaturesResourceDeclaration() {
    }
}
