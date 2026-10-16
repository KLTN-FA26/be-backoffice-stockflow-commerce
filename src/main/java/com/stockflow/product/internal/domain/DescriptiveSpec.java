package com.stockflow.product.internal.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * What a caller asks one descriptive attribute to hold. Exactly the field that matches the
 * attribute's type may be set; {@link ProductAttributeSet} refuses the rest.
 */
public record DescriptiveSpec(UUID attributeId, int sortOrder, List<UUID> valueIds,
                              String text, BigDecimal number, Boolean bool) {

    public DescriptiveSpec {
        valueIds = valueIds == null ? List.of() : List.copyOf(valueIds);
    }
}
