package com.stockflow.order.internal.domain;

import java.util.Collection;
import java.util.Set;

/**
 * Whether a customer's SKU was produced before (kltn-docs 14 BR-17; team plan 4B-3): its first production carries a
 * plate fee, a reorder of it none. A SKU counts as produced - its plate exists - once an order of it was confirmed and
 * not cancelled (goods are printed for confirmed orders only), or once a production order of it reached In Production
 * (QD4): the fee is charged once, so a quote sent for a produced SKU must not carry one.
 */
public interface SkuProductionHistory {

    /** Those of {@code skus} produced before, as given. */
    Set<String> producedBefore(Collection<String> skus);
}
