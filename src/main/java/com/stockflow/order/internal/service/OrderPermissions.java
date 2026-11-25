package com.stockflow.order.internal.service;

/**
 * Permission resources of the order module that a service checks as well as a controller. The controller layer reads
 * them through {@code OrderResources}; they live here because a service may not depend on the controller package.
 *
 * <p>Every check is a permission, never a role name (ADR-0008, lead's rule 2026-10-22).</p>
 */
public final class OrderPermissions {

    /** Quotes: drawing one up from a quote request, on the inbox or the chat card, is creating one (AUTH-2). */
    public static final String QUOTES = "sales-quotes";

    private OrderPermissions() {
    }
}
