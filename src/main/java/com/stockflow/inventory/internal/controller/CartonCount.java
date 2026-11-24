package com.stockflow.inventory.internal.controller;

/**
 * Pieces shown as cartons (kltn-docs 01 BR-09): stock is recorded in pieces, and every stock screen shows the number of
 * cartons beside them.
 *
 * <p>Stock is only ever whole cartons of the SKU's standard count (20 BR-14, 03 BR-13), so the division is exact. When
 * it is not - the SKU has no carton unit, or a carton holds other than the standard count - the cartons are left null
 * rather than rounded, the same rule as the stock summary report ({@code StockSummaryLine.cartons}): a rounded figure
 * would show a carton that is not there. A null quantity (withheld from a blind counter, SCRUM-146) stays null.</p>
 */
final class CartonCount {

    private CartonCount() {
    }

    /** {@code units} in cartons of {@code unitsPerCarton}; null when either is unknown or the division is not exact. */
    static Integer of(Integer units, Integer unitsPerCarton) {
        if (units == null || unitsPerCarton == null || unitsPerCarton <= 0 || units % unitsPerCarton != 0) {
            return null;
        }
        return units / unitsPerCarton;
    }
}
