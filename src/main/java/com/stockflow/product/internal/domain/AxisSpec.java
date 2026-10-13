package com.stockflow.product.internal.domain;

import java.util.List;
import java.util.UUID;

/** What a caller asks one variant axis to look like: the attribute, its place, and the option values in order. */
public record AxisSpec(UUID attributeId, int sortOrder, List<UUID> valueIds) {

    public AxisSpec {
        valueIds = valueIds == null ? List.of() : List.copyOf(valueIds);
    }
}
