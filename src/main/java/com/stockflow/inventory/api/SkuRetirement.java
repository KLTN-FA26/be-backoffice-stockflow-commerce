package com.stockflow.inventory.api;

/**
 * What the inventory module asks the module that owns SKUs before it edits an inventory item: "has this SKU been
 * retired?" A retired SKU (an {@code OBSOLETE} customer's SKU, kltn-docs 01 §5.2 - the only SKUs with an item since
 * V20270105000100) keeps its stock only to ship it or adjust it away, so its logistics and stock policy are frozen as
 * they were: still readable, never edited.
 *
 * <p>An interface in {@code inventory :: api} implemented <b>elsewhere</b> ({@code product}), for the same reason as
 * {@link ReplenishmentEligibility}: product already depends on inventory, so a call from inventory into
 * {@code product :: api} would close a cycle that {@code ModularityTest} rejects.</p>
 */
public interface SkuRetirement {

    /**
     * Whether the SKU is retired: an {@code OBSOLETE} customer's SKU, or one of a discontinued product. A SKU the
     * catalogue does not know is not retired.
     *
     * @param sku the SKU code as stored (upper case)
     */
    boolean retired(String sku);
}
