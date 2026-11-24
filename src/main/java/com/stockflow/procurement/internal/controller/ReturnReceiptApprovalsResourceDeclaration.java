package com.stockflow.procurement.internal.controller;

import com.stockflow.common.security.Action;
import com.stockflow.common.security.PermissionResource;

/**
 * Declares {@code procurement-return-receipt-approvals} (WF-05 decision Q-18): APPROVE confirms a return receipt that
 * holds an opened carton or loose units - the warehouse manager's, someone other than who counted it. A plain receipt
 * is still confirmed with {@code procurement-goods-receipts:UPDATE}. Declared ahead of its use by the WF-05 shared base
 * (PR-0), so the grant is seeded once (V20261216000050).
 *
 * <p>A marker, not a bean: {@code PermissionCatalogValidator} scans the classpath for {@code @PermissionResource}.</p>
 */
@PermissionResource(
        code = GoodsReceiptResources.RETURN_RECEIPT_APPROVALS,
        group = "Procurement",
        label = "Confirm a return receipt with opened cartons or loose units",
        route = "/procurement/goods-receipts",
        apiPath = "/api/v1/goods-receipts/returns/{receiptId}/confirmation",
        actions = {Action.APPROVE})
final class ReturnReceiptApprovalsResourceDeclaration {

    private ReturnReceiptApprovalsResourceDeclaration() {
    }
}
