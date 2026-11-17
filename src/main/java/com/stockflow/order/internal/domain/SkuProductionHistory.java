package com.stockflow.order.internal.domain;

import java.util.Collection;
import java.util.Set;

/**
 * Whether a customer's SKU was produced before (kltn-docs 14 BR-17; team plan 4B-3): its first production carries a
 * plate fee, a reorder of it none. Until production orders exist (team plan 4A-2, "a production order reached In
 * Production"), a SKU counts as produced once an order of it was confirmed - goods are printed for confirmed orders
 * only - and was not cancelled.
 */
public interface SkuProductionHistory {

    /** Those of {@code skus} produced before, as given. */
    Set<String> producedBefore(Collection<String> skus);
}
