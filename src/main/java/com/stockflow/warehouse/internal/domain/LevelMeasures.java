package com.stockflow.warehouse.internal.domain;

import java.math.BigDecimal;

/**
 * The physical measures of a shelf level (SCRUM-98), each optional: {@code null} means "not
 * checked". Golden-zone scoring (SCRUM-91) reads the elevation; putaway reads height and load.
 *
 * @param elevation    height of the level floor in the map unit, never negative
 * @param usableHeight clear height in the map unit, greater than zero
 * @param maxWeight    load limit in kilograms, greater than zero
 */
public record LevelMeasures(BigDecimal elevation, BigDecimal usableHeight, BigDecimal maxWeight) {

    public static final LevelMeasures NONE = new LevelMeasures(null, null, null);

    public LevelMeasures {
        if (elevation != null) {
            elevation = DomainChecks.measure("elevation", elevation);
            if (elevation.signum() < 0) {
                throw DomainChecks.invalid("elevation cannot be negative");
            }
        }
        usableHeight = usableHeight == null ? null : DomainChecks.positiveMeasure("usableHeight", usableHeight);
        maxWeight = maxWeight == null ? null : DomainChecks.positiveMeasure("maxWeight", maxWeight);
    }
}
