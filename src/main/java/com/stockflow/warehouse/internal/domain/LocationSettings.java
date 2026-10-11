package com.stockflow.warehouse.internal.domain;

import java.math.BigDecimal;

/**
 * What putaway and picking need to know about a storage location besides where it is.
 *
 * @param capacityUnits base units it holds; {@code null} = not checked, otherwise greater than zero
 * @param maxWeight     load limit in kilograms; {@code null} = not checked, otherwise greater than zero
 * @param pickable      whether picking may take stock from it (BR-08 needs a pick face then)
 * @param putawayTarget whether putaway may suggest it
 */
public record LocationSettings(Integer capacityUnits, BigDecimal maxWeight, boolean pickable,
                               boolean putawayTarget) {

    public LocationSettings {
        if (capacityUnits != null && capacityUnits <= 0) {
            throw DomainChecks.invalid("capacityUnits must be greater than zero");
        }
        maxWeight = maxWeight == null ? null : DomainChecks.positiveMeasure("maxWeight", maxWeight);
    }
}
