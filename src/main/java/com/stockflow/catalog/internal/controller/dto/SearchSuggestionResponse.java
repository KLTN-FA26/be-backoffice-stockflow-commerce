package com.stockflow.catalog.internal.controller.dto;

/** One search-box completion: the product title, its storefront slug, and the SKU the text matched (else its first SKU). */
public record SearchSuggestionResponse(String title, String slug, String sku) {}
