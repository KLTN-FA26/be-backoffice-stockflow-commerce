package com.stockflow.product.internal.controller.dto;

import com.stockflow.common.validation.ValidSku;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * A variant to add, or the new state of one.
 *
 * <p>Every field is optional at this edge because the service decides what is required: on a product
 * with axes the options are required and the SKU and name are generated when absent; on a product
 * without axes the SKU is required. On an update, a null {@code optionValueIds} keeps the options and a
 * missing sku or name keeps the current one. The SKU can change only while the variant is DRAFT, and so
 * can the options.</p>
 */
public record VariantRequest(
        @Schema(example = "CUP-12OZ-WHITE",
                description = "Optional on a product with axes (built from the product code and the option codes).")
        @ValidSku
        @Size(max = 64, message = "sku must be at most 64 characters")
        String sku,

        @Size(max = 255, message = "name must be at most 255 characters")
        String name,

        @Schema(description = "Exactly one dictionary value per axis of the product; the signature is derived "
                + "from them. Null on an update keeps the current options.")
        List<@NotNull UUID> optionValueIds,

        @PositiveOrZero(message = "position must not be negative")
        Integer position
) {
}
