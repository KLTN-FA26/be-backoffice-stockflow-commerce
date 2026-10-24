package com.stockflow.catalog.api;

import java.util.List;

/**
 * The storefront search (ADR-0009) for other modules: published products matching a text, best match first.
 *
 * <p>The same search {@code GET /api/v1/catalog/products} runs — accents and case ignored, typo-tolerant, exact SKU
 * first — so a product the assistant (ADR-0010) finds is one a signed-in user would find on the storefront. No price
 * and no stock: those stay with the customer-only endpoints.</p>
 */
public interface CatalogSearch {

    /** The most matches one search returns. */
    int MAX_RESULTS = 20;

    /**
     * @param text  1..100 characters
     * @param limit 1..{@value #MAX_RESULTS}
     * @throws com.stockflow.common.error.BusinessException {@code VALIDATION_FAILED} for a blank or overlong text or a
     *         limit out of range
     */
    List<CatalogMatch> search(String text, int limit);
}
