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
 * <p>Three values only (13 §4.4 step 4). Out of stock never stops a customer: a customer's SKU is made to order, so
 * Sales still quotes it and a Production Order makes up the shortfall (13 edge case "Khách muốn mua SKU riêng
 * {@code Out of stock}").</p>
 */
public enum Availability {

    /** Above the low-stock threshold. */
    IN_STOCK,

    /** Some whole cartons left, at or under the threshold - shown with a warning. */
    LOW_STOCK,

    /** Not one whole carton to promise; the customer can still ask Sales for a quote. */
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
}
