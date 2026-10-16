package com.stockflow.product.internal.domain;

import java.util.List;
import java.util.UUID;

/**
 * What a caller asks one variant axis to look like: the attribute, its place, and the option values.
 * The order of {@code valueIds} is not stored (an option row has no position); options are shown in
 * the dictionary's order, the value's sort order and then its code.
 */
public record AxisSpec(UUID attributeId, int sortOrder, List<UUID> valueIds) {

    public AxisSpec {
        valueIds = valueIds == null ? List.of() : List.copyOf(valueIds);
    }
}
