package com.stockflow.product.internal.controller.dto;

import java.util.List;
import java.util.UUID;

public record AttributeResponse(UUID attributeId, String code, String name, String dataType, String unit,
                                boolean filterable, boolean searchable, int sortOrder, boolean active, long version,
                                List<AttributeValueResponse> values) {
}
