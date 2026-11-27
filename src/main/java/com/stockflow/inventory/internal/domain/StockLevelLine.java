package com.stockflow.inventory.internal.domain;

/**
 * One (location, status) subtotal of a SKU; the input the warehouse-level roll-up sums.
 *
 * @param onHand      units of the cartons in stock there: each carton's count, summed
 * @param reserved    units of those cartons a live allocation names
 * @param cartons     the cartons in stock there, counted - not {@code onHand} divided by a standard count, which is
 *                    no whole number once cartons of two counts stand side by side (ADR-0011, step 3e)
 * @param heldCartons of those cartons, the ones a live allocation names
 */
public record StockLevelLine(LocationId location, StockStatus status, int onHand, int reserved, int cartons,
                             int heldCartons) {
}
