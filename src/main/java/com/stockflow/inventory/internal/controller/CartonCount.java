package com.stockflow.inventory.internal.controller;

/**
 * The cartons of one stock row (kltn-docs 01 BR-09): every stock screen shows the number of cartons beside the units.
 *
 * <p>Since ADR-0011 step 3d a stock row is one carton, so its cartons are read from the carton - one while it holds
 * units, none once they are zero - not worked out by dividing its units by the SKU's standard count, which left a
 * carton of another count with no figure at all (step 3e). A null quantity (withheld from a blind counter,
 * SCRUM-146) stays null.</p>
 */
final class CartonCount {

    private CartonCount() {
    }

    /** The cartons a one-carton row's {@code units} stand for: 1 while positive, 0 at zero, null while withheld. */
    static Integer ofOneCarton(Integer units) {
        if (units == null) {
            return null;
        }
        return units > 0 ? 1 : 0;
    }
}
