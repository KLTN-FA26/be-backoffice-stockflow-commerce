package com.stockflow.product.internal.controller.dto;

import com.stockflow.product.api.ProductKind;
import com.stockflow.product.api.TaxClass;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Request body for creating a product master row. Without {@code axes} the product is created with
 * one default variant whose SKU is the code. With axes, its axes and every combination of their active
 * options are created in the same transaction, SKUs by the code rule ({@code CODE-VALUE-VALUE}) and the
 * first combination as the default variant; anything refused (a SKU taken, a matrix over the limit)
 * leaves no product behind. Images are then added per variant ({@code /variants/{variantId}/media}).
 * Logistics and stock policy are not part of the catalog flow: they belong to the variant's inventory item
 * ({@code /api/v1/inventory/items/{id}}) and are set by the warehouse side, which holds
 * {@code inventory-inventory-items:UPDATE} (warehouse manager, inventory planner). Catalog roles hold no grant on
 * that resource by design.
 *
 * <p>{@code code} is upper-cased by the server. {@code categoryId} is not {@code @NotNull}: a product
 * can be created without one, and {@code submit()} is what refuses to move it to approval without a
 * category. Requiring it here too would duplicate that rule at two layers that could drift apart.</p>
 */
@Schema(description = "Create a product master row")
public record CreateProductRequest(

        @Schema(example = "CUP-12OZ")
        @NotBlank(message = "code is required")
        @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_-]{0,49}",
                message = "code is 1-50 letters, digits, '_' or '-', starting with a letter or digit")
        String code,

        @NotBlank(message = "name is required")
        @Size(max = 255, message = "name must be at most 255 characters")
        String name,

        @NotBlank(message = "nameEn is required")
        @Size(max = 300, message = "nameEn must be at most 300 characters")
        String nameEn,

        UUID brandId,

        UUID categoryId,

        String shortDescription,

        String description,

        String descriptionEn,

        @NotNull(message = "taxClass is required")
        TaxClass taxClass,

        @Schema(description = "STANDARD (sold as it is) or CUSTOMIZABLE (printed to a design); STANDARD when absent")
        ProductKind kind,

        @Schema(description = "Variant axes; when present, every combination of their active options is created "
                + "with the product, SKUs by the code rule, the first combination as the default variant")
        List<ReplaceAxesRequest.@NotNull(message = "an axis must not be null") @Valid Axis> axes
) {
}
