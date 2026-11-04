package com.stockflow.product.internal.controller.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record ProductAttributesResponse(UUID productId, List<Axis> axes, List<Descriptive> descriptives) {

    public record Axis(UUID attributeId, String code, String name, String dataType, int sortOrder,
                       List<Option> options) {
    }

    public record Option(UUID valueId, String code, String label, String hexColor, BigDecimal valueNumber,
                         boolean active, boolean usedByVariant) {
    }

    public record Descriptive(UUID attributeId, String code, String name, String dataType, String unit,
                              int sortOrder, List<Value> values, String text, BigDecimal number, Boolean bool) {
    }

    public record Value(UUID valueId, String code, String label, boolean active) {
    }
}
