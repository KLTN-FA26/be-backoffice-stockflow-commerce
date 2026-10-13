package com.stockflow.product.internal.controller.dto;

import java.util.UUID;

public record CategoryAttributeResponse(UUID attributeId, String attributeCode, String attributeName, String dataType,
                                        String defaultRole, boolean required, int sortOrder) {
}
