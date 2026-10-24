package com.stockflow.fulfillment.internal.controller.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * A manual edit of the whole plan (09 §4.2 step 3, BR-11), all or nothing: every vehicle of the plan with its boxes,
 * and the tray. Every package of the plan appears exactly once. A box is the layout's box: {@code ref} (the package
 * id), the corner {@code xMm, yMm, zMm} and the placed sizes {@code wMm, hMm, dMm} along x, y, z; {@code orientation}
 * is optional (the sizes decide). Extra layout fields are ignored.
 */
public record LoadPlacementsRequest(@NotNull Long expectedVersion,
                                    @NotNull @Size(max = 100) List<@Valid @NotNull Vehicle> vehicles,
                                    @Size(max = 2000) List<@NotNull UUID> tray) {

    public record Vehicle(@NotNull Integer seq, @NotNull @Size(max = 2000) List<@Valid @NotNull Box> boxes) {
    }

    public record Box(@NotNull UUID ref, @NotNull Integer xMm, @NotNull Integer yMm, @NotNull Integer zMm,
                      @NotNull Integer wMm, @NotNull Integer hMm, @NotNull Integer dMm,
                      @Size(max = 3) String orientation) {
    }
}
