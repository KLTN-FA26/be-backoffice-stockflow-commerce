package com.stockflow.inventory.api;

import java.math.BigDecimal;

/**
 * What stands at one location of a warehouse and what putaway trips hold there (#456): the load of a bin, for the
 * location list. Measured as the capacity board measures a warehouse ({@link OccupiedVolume}): every carton IN_STOCK,
 * whatever its stock status, takes its carton unit's length x width x height and weighs that unit's full weight.
 *
 * @param cartons            the cartons IN_STOCK there
 * @param skus               how many SKUs they are of
 * @param units              the units in them
 * @param occupiedM3         Σ their volume, six decimals; a carton without a size adds nothing
 * @param occupiedKg         Σ their full weight
 * @param cartonsWithoutSize those whose carton unit lacks a dimension, so occupiedM3 is short by them
 * @param heldByTripsM3      the volume putaway trips on their way hold there (kltn-docs 05 BR-16)
 * @param heldByTripsKg      the weight they hold there
 * @param underOpenCount     a cycle count still open (PLANNED or IN_PROGRESS) counts a slot there
 */
public record LocationLoad(String locationCode, int cartons, int skus, int units, BigDecimal occupiedM3,
                           BigDecimal occupiedKg, int cartonsWithoutSize, BigDecimal heldByTripsM3,
                           BigDecimal heldByTripsKg, boolean underOpenCount) {
}
