package com.stockflow.product.internal.domain;

import java.math.BigDecimal;
import java.util.UUID;

/** One stored value of a descriptive attribute: a dictionary value, or text, a number or a flag. */
public record DescriptiveValue(UUID id, UUID valueId, String text, BigDecimal number, Boolean bool) {
}
