package com.stockflow.product.api;

/** Tax treatment of a product, for the storefront/checkout tax calculation (WBS 3.1.1.2). */
public enum TaxClass {
    STANDARD,
    REDUCED,
    EXEMPT
}
