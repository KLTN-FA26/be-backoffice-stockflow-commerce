package com.stockflow.order.internal.controller;

import com.stockflow.common.security.Action;
import com.stockflow.common.security.PermissionResource;

/**
 * Declares {@code sales-quote-conversions}, guarding {@code POST /quotes/{id}/conversion} on {@link QuoteController},
 * which already carries the {@code sales-quotes} resource.
 *
 * <p>A marker, not a bean, as {@code OrderCancellationsResourceDeclaration}: {@code PermissionCatalogValidator} scans
 * the classpath for {@code @PermissionResource}.</p>
 */
@PermissionResource(
        code = OrderResources.QUOTE_CONVERSIONS,
        group = "Sales",
        label = "Quote conversions",
        route = "/sales/quotes",
        apiPath = "/api/v1/quotes/{quoteId}/conversion",
        actions = {Action.CREATE})
final class QuoteConversionsResourceDeclaration {

    private QuoteConversionsResourceDeclaration() {
    }
}
