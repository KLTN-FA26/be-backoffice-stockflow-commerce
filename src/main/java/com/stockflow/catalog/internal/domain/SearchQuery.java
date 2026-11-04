package com.stockflow.catalog.internal.domain;

import java.util.List;

/**
 * What a shopper typed, reduced to what the database is asked.
 *
 * <p>Built only by {@link SearchQueryNormalizer}. {@code text} is lower case and accent-folded
 * (Vietnamese diacritics and {@code đ} removed), with every run of non-letters collapsed to one
 * space; {@code terms} are its words. Both contain letters and digits only, which is the whole
 * reason the repository can hand them to {@code to_tsquery} without escaping anything.</p>
 *
 * @param text     the folded text, or empty when nothing searchable was typed
 * @param terms    the words of {@code text}, in order
 * @param skuKeys  forms the input may take as a SKU code (lower case, e.g. {@code sofa-3s-grey}),
 *                 compared with {@code lower(sku)}; empty when no term was typed
 */
public record SearchQuery(String text, List<String> terms, List<String> skuKeys) {

    public static final SearchQuery NONE = new SearchQuery("", List.of(), List.of());

    public SearchQuery {
        terms = List.copyOf(terms);
        skuKeys = List.copyOf(skuKeys);
    }

    /** True when there is nothing to match: no input, or input made only of punctuation. */
    public boolean isEmpty() {
        return terms.isEmpty();
    }

    /** The last word, which is matched as a prefix because the shopper may still be typing it. */
    public String lastTerm() {
        return terms.getLast();
    }

    /** Every word but the last, space separated; empty for a single-word query. */
    public String head() {
        return String.join(" ", terms.subList(0, terms.size() - 1));
    }
}
