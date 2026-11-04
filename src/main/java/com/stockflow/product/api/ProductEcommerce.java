package com.stockflow.product.api;

/** Canonical selling content in product.products; catalog retains only a read projection. */
public record ProductEcommerce(
        String slug, String seoTitle, String seoDescription, boolean everPublished, long version) {}
