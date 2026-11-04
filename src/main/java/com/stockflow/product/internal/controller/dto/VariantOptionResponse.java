package com.stockflow.product.internal.controller.dto;

import java.util.UUID;

/** The option a variant takes on one axis. */
public record VariantOptionResponse(UUID attributeId, String attributeCode, UUID valueId, String valueCode,
                                    String label) {
}
