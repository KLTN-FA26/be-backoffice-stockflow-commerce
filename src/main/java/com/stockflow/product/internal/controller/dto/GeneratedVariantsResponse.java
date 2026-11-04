package com.stockflow.product.internal.controller.dto;

import java.util.List;

/** What a generation did: the variants it created, and how many combinations already existed. */
public record GeneratedVariantsResponse(List<VariantResponse> created, int skipped) {
}
