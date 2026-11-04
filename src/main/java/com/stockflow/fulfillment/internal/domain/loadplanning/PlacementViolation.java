package com.stockflow.fulfillment.internal.domain.loadplanning;

/**
 * One broken rule.
 *
 * @param ref the carton that breaks it; null for {@link ViolationType#OVERWEIGHT}, which is the vehicle's
 */
public record PlacementViolation(String ref, ViolationType rule) { }
