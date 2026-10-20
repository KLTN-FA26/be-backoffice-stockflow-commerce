package com.stockflow.product.api;

import java.util.List;
import java.util.UUID;

/**
 * Input to {@link ProductService#create}. Without {@code axes} the product is born with one default
 * variant whose SKU is the product code. With axes it is born with its axes, their options and every
 * combination of the active options, each a variant whose SKU follows the code rule; the first
 * combination is the default variant (spec D2), so no placeholder SKU ever exists. Each variant comes
 * with its inventory item; logistics live on the inventory item and images on a variant, not here.
 */
public record CreateProductCommand(
        String code,
        String name,
        String nameEn,
        UUID brandId,
        UUID categoryId,
        String shortDescription,
        String description,
        String descriptionEn,
        TaxClass taxClass,
        ProductKind kind,
        List<Axis> axes
) {

    public CreateProductCommand {
        axes = axes == null ? List.of() : List.copyOf(axes);
    }

    /** One variant axis: a dictionary attribute and the values it offers. A null {@code sortOrder} means its place in the list. */
    public record Axis(UUID attributeId, Integer sortOrder, List<UUID> optionValueIds) {
    }
}
