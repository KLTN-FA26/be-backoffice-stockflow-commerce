package com.stockflow.product.internal.controller.dto;

import com.stockflow.product.api.ProductStatus;
import com.stockflow.product.api.TaxClass;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/** A product's VAT group (WF-05 D-8), found from a SKU or just changed. */
@Schema(description = "The VAT group of a SKU's product; taxClass null while it was never set")
public record ProductTaxClassResponse(String sku, UUID productId, String productCode, String productName,
                                      ProductStatus status, TaxClass taxClass, long version) {
}
