package com.stockflow.product.api;

import java.util.Collection;
import java.util.List;

/**
 * What the product module asks other modules: "which of your open documents still use these SKUs?"
 * (SCRUM-461, BR-PRD-005).
 *
 * <p>An interface in {@code product :: api} implemented <b>elsewhere</b> ({@code procurement},
 * {@code order}), and not a call from product into them: {@code catalog} depends on {@code product} and
 * {@code order} on {@code catalog}, so product calling order would close a cycle
 * ({@code product → order → catalog → product}) that {@code ModularityTest} rejects. The product service is
 * handed every implementation by Spring and depends on this interface only.</p>
 *
 * <p>The answer informs a person; it never blocks anything. kltn-docs 01 step 12 and 02 (edge cases)
 * let a discontinued product's open orders and purchase orders run to the end.</p>
 */
public interface SkuReferenceProbe {

    /**
     * Open documents that refer to any of {@code skus}, at most {@code limit} of them, most recent first.
     * Every entry has its own {@link SkuReference.Kind}; an implementation answers for one kind.
     */
    List<SkuReference> openReferences(Collection<String> skus, int limit);
}
