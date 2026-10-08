package com.stockflow.catalog.internal.domain;

/**
 * What the catalog tells a signed-in customer about a SKU's stock (docs 13 §4.2 step 4, BR-03).
 *
 * <p>An indicator, not a number. The customer needs to know whether they can buy now; the exact
 * available-to-promise figure is the business's stock position, and even behind a login it is
 * intelligence that would leak from one wholesale customer to the next.</p>
 *
 * <p>No {@code PRE_ORDER} yet: docs 13 lists it for "ATP ≤ 0 but the policy allows pre-ordering",
 * and no such policy exists anywhere in the data model. Until one does, out of stock is out of
 * stock.</p>
 */
public enum Availability {

    /** Above the low-stock threshold. */
    IN_STOCK,

    /** Some left, at or under the threshold — still buyable, shown with a warning. */
    LOW_STOCK,

    /** Nothing to promise; the product stays visible but cannot be bought. */
    OUT_OF_STOCK;

    /**
     * @param atp       available to promise; negative is treated as nothing
     * @param threshold the low-stock threshold, 0 meaning "never warn"
     */
    public static Availability of(int atp, int threshold) {
        if (threshold < 0) {
            throw new IllegalArgumentException("threshold must not be negative: " + threshold);
        }
        if (atp <= 0) {
            return OUT_OF_STOCK;
        }
        return atp <= threshold ? LOW_STOCK : IN_STOCK;
    }
}
