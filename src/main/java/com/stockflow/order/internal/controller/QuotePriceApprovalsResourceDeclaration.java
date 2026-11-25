package com.stockflow.order.internal.controller;

import com.stockflow.common.security.Action;
import com.stockflow.common.security.PermissionResource;

/**
 * Declares {@code sales-quote-price-approvals}, guarding {@code POST /quotes/{id}/price-approval} and
 * {@code POST /quotes/{id}/price-rejection} on {@link QuoteController}, which already carries the {@code sales-quotes}
 * resource (kltn-docs 14 BR-10 as changed on 2026-11-26).
 *
 * <p>A marker, not a bean, as {@link QuoteConversionsResourceDeclaration}: {@code PermissionCatalogValidator} scans the
 * classpath for {@code @PermissionResource}.</p>
 */
@PermissionResource(
        code = OrderResources.QUOTE_PRICE_APPROVALS,
        group = "Sales",
        label = "Quote price approvals",
        route = "/sales/quotes",
        apiPath = "/api/v1/quotes/{quoteId}/price-approval",
        actions = {Action.APPROVE})
final class QuotePriceApprovalsResourceDeclaration {

    private QuotePriceApprovalsResourceDeclaration() {
    }
}
