package com.stockflow.product.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * A customer's own SKU as the back office lists it for a quote: the SKU's own fields, as before, and what a quote line
 * of it copies (R5-4), so the editor shows a line's total before the draft is saved.
 */
public record CustomerSkuResponse(
        @Schema(description = "The SKU's own id (renamed from variantId, V20270105000100): what its lifecycle endpoints "
                + "and a chat card name") UUID id,
        UUID productId,
        @Schema(example = "CUP12-0312345678-01") String sku,
        String name,
        UUID ownerCustomerId,
        UUID designSnapshotId,
        @Schema(description = "The catalogue variant the design was drawn on, whose carton it is packed in")
        UUID sourceVariantId,
        String sourceSku,
        @Schema(allowableValues = {"ACTIVE", "BLOCKED", "OBSOLETE"}) String status,
        @Schema(description = "R5-4: pieces in the SKU's carton, as a quote line fixes them; null while it has no single "
                + "carton unit (a quote refuses it with CARTON_SPEC_MISSING)", example = "1000")
        Integer unitsPerCarton,
        @Schema(description = "R5-4: its product's tax class; null while the product has none", example = "STANDARD")
        String taxClass,
        @Schema(description = "R5-4: that class's VAT rate today, a percentage - what a quote line drawn up today copies; "
                + "null without a class", example = "8")
        BigDecimal taxRate,
        @Schema(description = "The owner customer's name, so a page of several customers' SKUs needs no customer lookup")
        String ownerCustomerName,
        @Schema(description = "The owner as a document code spells them: their tax code, or KH and eight characters of "
                + "the id", example = "0312345678")
        String ownerCustomerCode) {
}
