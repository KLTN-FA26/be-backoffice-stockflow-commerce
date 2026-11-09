package com.stockflow.catalog.internal.domain;

/**
 * What the catalog tells a signed-in customer about one of its own SKUs' stock (kltn-docs 13 §4.4 step 4, BR-07).
 *
 * <p>An indicator, not a number. The customer needs to know whether they can buy now; the exact
 * available-to-promise figure is the business's stock position, and even behind a login it is
 * intelligence that would leak from one wholesale customer to the next.</p>
 *
 * <p>Counted in cartons (13 BR-12, open-questions A9, D28): a customer orders whole cartons, so stock short of one
 * carton is nothing to promise, and the low-stock threshold is a number of cartons too.</p>
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
     * @param atp       available to promise, in the unit the threshold counts; negative is treated as nothing
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

    /**
     * The indicator of stock counted in pieces, for a SKU sold by the carton: only whole cartons count (13 BR-07, BR-12
     * - every carton holds the SKU's standard number of pieces).
     *
     * @param atpUnits           available to promise, in pieces; negative is treated as nothing
     * @param unitsPerCarton     the SKU's standard pieces per carton, at least 1
     * @param thresholdInCartons the low-stock threshold, in cartons, 0 meaning "never warn"
     */
    public static Availability ofCartons(int atpUnits, int unitsPerCarton, int thresholdInCartons) {
        if (unitsPerCarton < 1) {
            throw new IllegalArgumentException("unitsPerCarton must be at least 1: " + unitsPerCarton);
        }
        return of(Math.max(atpUnits, 0) / unitsPerCarton, thresholdInCartons);
    }
}
