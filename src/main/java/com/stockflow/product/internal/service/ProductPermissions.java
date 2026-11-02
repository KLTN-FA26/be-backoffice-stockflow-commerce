package com.stockflow.product.internal.service;

/**
 * Permission resources of the product module that a service checks as well as a controller. The controller layer
 * reads them through {@code ProductResources}; they live here because a service may not depend on the controller
 * package (the same split as {@code FulfillmentPermissions}).
 */
public final class ProductPermissions {

    /**
     * APPROVE: suspend, resume and discontinue a product (kltn-docs 01 §5.1), and approve a product one submitted
     * oneself (BR-PRD-003). Granted to SYSTEM_ADMIN only (V20261019000700); a permission, not the role name, so the
     * role matrix decides who holds it.
     */
    public static final String LIFECYCLE = "product-lifecycle";

    private ProductPermissions() {
    }
}
