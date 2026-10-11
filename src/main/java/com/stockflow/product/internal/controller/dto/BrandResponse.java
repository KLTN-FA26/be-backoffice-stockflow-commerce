package com.stockflow.product.internal.controller.dto;

import java.util.UUID;

public record BrandResponse(UUID brandId, String code, String name, String slug, String logoUrl, boolean active,
                            long version) {
}
