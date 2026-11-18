package com.stockflow.product.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * The parametric model of a template, as the storefront's studio reads it: {@code kind} says which fields apply. A cup
 * takes the cone's sizes and band offsets, a bag its width, gusset, height, top fold, glue flap and bottom fold; all
 * lengths in millimetres. A bag's {@code glueFlapMm} and {@code bottomFoldMm} may be left out: they then take
 * {@code BagModel}'s defaults (20 mm, and 0.6 x the gusset rounded to hundredths), an assumption awaiting the workshop.
 */
@Schema(description = "parametric-cup: topDiameterMm, bottomDiameterMm, heightMm, rimMm, seamMm, bandTopOffsetMm, "
        + "bandBottomOffsetMm, material (paper-white | kraft | pet-clear). parametric-bag: widthMm, gussetMm, heightMm, "
        + "topFoldMm, glueFlapMm (0-100, default 20), bottomFoldMm (0-gussetMm, default round2(0.6 x gussetMm)), "
        + "handle (twisted | flat | none), material (kraft-brown | kraft-white).")
public record ProductModelRequest(
        @Schema(example = "parametric-cup")
        @NotBlank(message = "model.kind is required")
        String kind,
        Double topDiameterMm,
        Double bottomDiameterMm,
        Double heightMm,
        Double rimMm,
        Double seamMm,
        Double bandTopOffsetMm,
        Double bandBottomOffsetMm,
        Double widthMm,
        Double gussetMm,
        Double topFoldMm,
        Double glueFlapMm,
        Double bottomFoldMm,
        String handle,
        String material
) {
}
