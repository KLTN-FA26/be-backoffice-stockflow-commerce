package com.stockflow.product.api;

/**
 * A paper bag printed on its four sides: front and back as wide as the bag, the two gussets as deep as it, all as tall
 * as the bag less the top fold that turns inward. Bottom and handles are not printed.
 *
 * @param widthMm   front and back width
 * @param gussetMm  side depth
 * @param heightMm  height, top fold included
 * @param topFoldMm the band folded inward at the top; shorter than the bag
 * @param handle    {@code twisted}, {@code flat} or {@code none}
 * @param material  {@code kraft-brown} or {@code kraft-white}
 */
public record BagModel(double widthMm, double gussetMm, double heightMm, double topFoldMm, String handle,
                       String material) implements ProductModel {

    public static final String KIND = "parametric-bag";

    @Override
    public String kind() {
        return KIND;
    }
}
