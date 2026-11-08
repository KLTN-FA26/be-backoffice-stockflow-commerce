package com.stockflow.product.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * The parametric model of a template, as the storefront's studio reads it: {@code kind} says which fields apply. A cup
 * takes the cone's sizes and band offsets, a bag its width, gusset, height and top fold; all lengths in millimetres.
 */
@Schema(description = "parametric-cup: topDiameterMm, bottomDiameterMm, heightMm, rimMm, seamMm, bandTopOffsetMm, "
        + "bandBottomOffsetMm, material (paper-white | kraft | pet-clear). parametric-bag: widthMm, gussetMm, heightMm, "
        + "topFoldMm, handle (twisted | flat | none), material (kraft-brown | kraft-white).")
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
        String handle,
        String material
) {
}
