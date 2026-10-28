package com.stockflow.product.internal.domain;

/**
 * What a brand or category a product points at is, as the product's service needs to know it: absent (404),
 * retired by deactivation (it stays on the products that already chose it, a product cannot newly choose it),
 * or in use.
 */
public enum ReferenceStatus {
    MISSING,
    INACTIVE,
    ACTIVE
}
