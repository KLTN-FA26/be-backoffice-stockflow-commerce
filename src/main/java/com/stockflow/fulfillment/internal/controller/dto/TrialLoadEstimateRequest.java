package com.stockflow.fulfillment.internal.controller.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * The trial estimate (SCRUM-474 TRIAL-FLOW, LP2-9): a box list as the EasyCargo sheet reads, loaded on the chosen
 * vehicles without being stored. The browser has already turned the sheet into millimetres (Length along the vehicle).
 *
 * @param lines          the box lines, at most {@code 10 000} here as a parse guard; the real cap is the estimate's,
 *                       3 000 boxes in all ({@code LOAD_ESTIMATE_TOO_MANY_CARTONS})
 * @param vehicleTypeIds the vehicle types the plan may use; empty or absent: every active type
 * @param selectionMode  SINGLE_TYPE ("Một loại", the default) or FEWEST_VEHICLES ("Ít xe nhất")
 */
public record TrialLoadEstimateRequest(@NotEmpty @Size(max = 10_000) List<@Valid @NotNull Line> lines,
                                       @Size(max = 20) List<@NotNull UUID> vehicleTypeIds,
                                       @Pattern(regexp = "SINGLE_TYPE|FEWEST_VEHICLES") String selectionMode) {

    /** Millimetres are capped at 100 m, far beyond any vehicle, so a volume always fits a long. */
    public static final int MAX_MM = 100_000;

    /**
     * @param name      what the box is (the sheet's Description), shown in the legend; blank: the group, else a line number
     * @param group     the sheet's Group, a label only
     * @param rotatable the sheet's Rotable; absent: true, as the sheet's default
     */
    public record Line(@Size(max = 200) String name, @Size(max = 100) String group,
                       @NotNull @Positive @Max(MAX_MM) Integer lengthMm, @NotNull @Positive @Max(MAX_MM) Integer widthMm,
                       @NotNull @Positive @Max(MAX_MM) Integer heightMm, @NotNull @Positive Integer quantity,
                       Boolean rotatable) { }
}
