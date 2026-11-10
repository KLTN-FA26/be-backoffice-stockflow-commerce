package com.stockflow.warehouse.internal.domain;

import java.math.BigDecimal;

/**
 * The physical measures of a shelf level (SCRUM-98). Golden-zone scoring (SCRUM-91) reads the elevation;
 * putaway reads height and load.
 *
 * <p>Elevation and load are optional: {@code null} means "not checked". The clear height is required of every level
 * written now ({@link Shelf} refuses a level without one, kltn-docs 06 BR-19), but may be {@code null} here because a
 * legacy level read back from storage may have none; its bins then have no volume.</p>
 *
 * @param elevation    height of the level floor in the map unit, never negative
 * @param usableHeight clear height in the map unit, greater than zero; a bin's volume is its width x length x this
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
