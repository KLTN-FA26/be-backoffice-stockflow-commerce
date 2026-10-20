package com.stockflow.product.internal.controller.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record AttributeValueResponse(UUID valueId, String code, String label, BigDecimal valueNumber,
                                     String hexColor, int sortOrder, boolean active, long version) {
}
