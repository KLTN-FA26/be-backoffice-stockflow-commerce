package com.stockflow.warehouse.internal.domain;

import java.math.BigDecimal;

/**
 * What putaway and picking need to know about a storage location besides where it is.
 *
 * <p>No capacity: a bin's volume is computed from its size and its level's clear height (kltn-docs 06 BR-19,
 * {@code BinSize}), never typed in, so there is nothing here to keep in step with the map.</p>
 *
 * @param maxWeight     load limit in kilograms; {@code null} = not checked, otherwise greater than zero
 * @param pickable      whether picking may take stock from it (BR-08 needs a pick face then)
 * @param putawayTarget whether putaway may suggest it
 */
public record LocationSettings(BigDecimal maxWeight, boolean pickable, boolean putawayTarget) {

    public LocationSettings {
        maxWeight = maxWeight == null ? null : DomainChecks.positiveMeasure("maxWeight", maxWeight);
    }
}
