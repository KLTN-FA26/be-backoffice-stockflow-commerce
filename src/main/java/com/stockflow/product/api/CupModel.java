package com.stockflow.product.api;

/**
 * A paper or PET cup: a truncated cone whose wall is printed on one band, unrolled flat into an annular sector (or a
 * rectangle, when the wall is nearly straight).
 *
 * @param topDiameterMm      diameter at the rim
 * @param bottomDiameterMm   diameter at the base; not larger than the rim
 * @param heightMm           vertical height of the wall
 * @param rimMm              the rolled rim, for the 3D view only
 * @param seamMm             the glue flap where the band overlaps itself; hidden once assembled
 * @param bandTopOffsetMm    unprinted distance from the rim along the wall
 * @param bandBottomOffsetMm unprinted distance from the base along the wall
 * @param material           {@code paper-white}, {@code kraft} or {@code pet-clear}
 */
public record CupModel(double topDiameterMm, double bottomDiameterMm, double heightMm, double rimMm, double seamMm,
                       double bandTopOffsetMm, double bandBottomOffsetMm, String material) implements ProductModel {

    public static final String KIND = "parametric-cup";

    @Override
    public String kind() {
        return KIND;
    }
}
