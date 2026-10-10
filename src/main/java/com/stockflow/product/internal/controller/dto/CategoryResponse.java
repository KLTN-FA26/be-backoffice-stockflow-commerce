package com.stockflow.product.internal.controller.dto;

import java.util.UUID;

/** One node of the category tree. {@code path} is {@code /ROOT/CHILD}, {@code depth} 0 for a root. */
public record CategoryResponse(UUID categoryId, UUID parentId, String code, String name, String slug, String path,
                               int depth, int sortOrder, String imageUrl, String seoTitle, String seoDescription,
                               boolean active, long version) {
}
