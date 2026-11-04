package com.stockflow.catalog.internal.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Catalog search ranking and limits ({@code stockflow.catalog.search}, SCRUM-182).
 *
 * <p>The score of a product is
 * {@code textRankWeight * ts_rank_cd + similarityWeight * similarity + exactSkuWeight * [SKU typed exactly]
 * + inStockWeight * [available to promise > 0]}. {@code ts_rank_cd} and {@code similarity} are both in
 * 0..1, so the defaults say: an exact SKU beats everything text can do, and being in stock is a
 * tie-breaker between products that match about equally, not a reason to bury a better match.</p>
 *
 * @param textRankWeight   weight of the full-text rank (title matches count five times a description match)
 * @param similarityWeight weight of the trigram similarity between the query and the title
 * @param exactSkuWeight   added when the query is exactly one of the product's SKU codes
 * @param inStockWeight    added when any SKU of the product has stock available to promise
 * @param fallbackSimilarity minimum title similarity when the exact search finds nothing (typos)
 * @param rankingWindow    how many best matches are ranked and pageable; stock is not in the database
 *                         (another module's schema), so ranking by it happens in memory over this window.
 *                         {@code totalElements} is the size of the window, never more
 */
@ConfigurationProperties(prefix = "stockflow.catalog.search")
public record SearchProperties(
        @DefaultValue("1.0") double textRankWeight,
        @DefaultValue("1.0") double similarityWeight,
        @DefaultValue("10.0") double exactSkuWeight,
        @DefaultValue("0.25") double inStockWeight,
        @DefaultValue("0.2") double fallbackSimilarity,
        @DefaultValue("500") int rankingWindow) {

    public SearchProperties {
        if (textRankWeight < 0 || similarityWeight < 0 || exactSkuWeight < 0 || inStockWeight < 0) {
            throw new IllegalArgumentException("stockflow.catalog.search weights must not be negative");
        }
        if (fallbackSimilarity <= 0 || fallbackSimilarity > 1) {
            throw new IllegalArgumentException(
                    "stockflow.catalog.search.fallback-similarity must be in (0, 1]");
        }
        if (rankingWindow < 1 || rankingWindow > 5000) {
            throw new IllegalArgumentException(
                    "stockflow.catalog.search.ranking-window must be between 1 and 5000");
        }
    }
}
