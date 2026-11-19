package com.stockflow.product.api;

/**
 * A paper bag printed on its four sides: front and back as wide as the bag, the two gussets as deep as it, all as tall
 * as the bag less the top fold that turns inward. Bottom and handles are not printed.
 *
 * <p>The bag is printed as one flat sheet - die-cut, folded into a tube, glued along {@code glueFlapMm}, its bottom
 * folded from {@code bottomFoldMm} - as the workshop makes it (FE request 2026-11-18 BE-8/BE-9). Neither of those two
 * lengths comes from the {@code .glb} or changes a print area: like {@code topFoldMm}, they are edited freely.</p>
 *
 * @param widthMm      front and back width
 * @param gussetMm     side depth
 * @param heightMm     height, top fold included
 * @param topFoldMm    the band folded inward at the top; shorter than the bag
 * @param glueFlapMm   the strip along the sheet's left edge glued under the last side; 0 to 100
 * @param bottomFoldMm how deep the sheet runs below the printed sides to fold the bottom; 0 to the gusset
 * @param handle       {@code twisted}, {@code flat} or {@code none}
 * @param material     {@code kraft-brown} or {@code kraft-white}
 */
public record BagModel(double widthMm, double gussetMm, double heightMm, double topFoldMm, double glueFlapMm,
                       double bottomFoldMm, String handle, String material) implements ProductModel {

    public static final String KIND = "parametric-bag";

    /**
     * The glue flap a bag has when nobody said: a request that does not send it, or a template stored before the flap
     * existed. <b>An assumption awaiting the workshop's confirmation</b> - the value the back office pre-fills, kept
     * here, in one place, so the storefront and the server draw the same sheet from the same template.
     */
    public static final double DEFAULT_GLUE_FLAP_MM = 20;

    /** The share of the gusset a bottom fold takes when nobody said; see {@link #defaultBottomFoldMm}. */
    public static final double DEFAULT_BOTTOM_FOLD_SHARE = 0.6;

    /** A bag whose glue flap and bottom fold are the defaults ({@link #withDefaults}). */
    public BagModel(double widthMm, double gussetMm, double heightMm, double topFoldMm, String handle,
                    String material) {
        this(widthMm, gussetMm, heightMm, topFoldMm, DEFAULT_GLUE_FLAP_MM, defaultBottomFoldMm(gussetMm), handle,
                material);
    }

    /**
     * The bottom fold a bag of {@code gussetMm} has when nobody said: {@code round2(0.6 × gusset)}. <b>An assumption
     * awaiting the workshop's confirmation</b>, like {@link #DEFAULT_GLUE_FLAP_MM}. Rounded half up to hundredths as
     * the studio's {@code Math.round(x * 100) / 100} rounds, so both ends compute the same sheet.
     */
    public static double defaultBottomFoldMm(double gussetMm) {
        return Math.round(DEFAULT_BOTTOM_FOLD_SHARE * gussetMm * 100) / 100.0;
    }

    /**
     * A bag with the given measurements, the glue flap and bottom fold defaulted where they are null: the one place a
     * request without them, and a stored template written before them, get their values.
     */
    public static BagModel withDefaults(double widthMm, double gussetMm, double heightMm, double topFoldMm,
                                        Double glueFlapMm, Double bottomFoldMm, String handle, String material) {
        return new BagModel(widthMm, gussetMm, heightMm, topFoldMm,
                glueFlapMm == null ? DEFAULT_GLUE_FLAP_MM : glueFlapMm,
                bottomFoldMm == null ? defaultBottomFoldMm(gussetMm) : bottomFoldMm, handle, material);
    }

    @Override
    public String kind() {
        return KIND;
    }
}
