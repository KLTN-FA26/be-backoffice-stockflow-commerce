package com.stockflow.product.internal.controller;

import com.stockflow.product.internal.service.ProductPermissions;

/**
 * Permission resource codes owned by the product module. See {@code InventoryResources} for why
 * these are constants rather than string literals.
 */
public final class ProductResources {

    public static final String PRODUCTS = "product-products";
    public static final String ATTRIBUTES = "product-attributes";
    public static final String VARIANTS = "product-variants";
    /**
     * Suspend, resume, discontinue: the System Admin's (kltn-docs 01 §5.1), apart from approving and publishing; also
     * what lets an approver approve their own submission (BR-PRD-003, checked in the service).
     */
    public static final String LIFECYCLE = ProductPermissions.LIFECYCLE;

    private ProductResources() {
    }
}
