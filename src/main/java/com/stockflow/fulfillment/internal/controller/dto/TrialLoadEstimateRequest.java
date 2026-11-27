package com.stockflow.fulfillment.internal.controller.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * The trial estimate (SCRUM-474 TRIAL-FLOW, LP2-9): a box list as the EasyCargo sheet reads, loaded on the chosen
 * vehicles without being stored. The browser has already turned the sheet into millimetres (Length along the vehicle).
 *
 * @param lines          the box lines, at most {@code 10 000} here as a parse guard; the real cap is the estimate's,
 *                       3 000 boxes in all ({@code LOAD_ESTIMATE_TOO_MANY_CARTONS})
 * @param vehicleTypeIds the vehicle types the plan may use; empty or absent: every active type
 * @param selectionMode  any of the docs' modes (09 §4.2; WF4-TRIAL-EST): SINGLE_TYPE ("Một loại", the default),
 *                       FEWEST_VEHICLES ("Ít xe nhất"), KEEP_LINES ("Giữ nguyên dòng"), PRIORITY_FILL ("Ưu tiên + hàng
 *                       chèn"), OVER_CAPACITY ("Vượt sức chứa"). "Chỉnh tay" edits a stored plan and has no value here
 */
public record TrialLoadEstimateRequest(@NotEmpty @Size(max = 10_000) List<@Valid @NotNull Line> lines,
                                       @Size(max = 20) List<@NotNull UUID> vehicleTypeIds,
                                       @Pattern(regexp = "SINGLE_TYPE|FEWEST_VEHICLES|KEEP_LINES|PRIORITY_FILL"
                                               + "|OVER_CAPACITY") String selectionMode) {

    /** Millimetres are capped at 100 m, far beyond any vehicle, so a volume always fits a long. */
    public static final int MAX_MM = 100_000;

    /** A line's total weight is capped at 1 000 t, far beyond any vehicle's payload. */
    public static final String MAX_TOTAL_WEIGHT_KG = "1000000";

    /**
     * @param name      what the box is (the sheet's Description), shown in the legend; blank: the group, else a line number
     * @param group         the sheet's Group, a label only
     * @param totalWeightKg the sheet's Total weight in kilograms: all the line's boxes together (WF4-TRIAL-EST)
     * @param rotatable     the sheet's Rotable; absent: true, as the sheet's default
     * @param fill          the sheet's "Hàng chèn": the line only tops up the space the main cargo leaves; read only
     *                      in PRIORITY_FILL. Absent: false (main cargo)
     */
    public record Line(@Size(max = 200) String name, @Size(max = 100) String group,
                       @NotNull @Positive @Max(MAX_MM) Integer lengthMm, @NotNull @Positive @Max(MAX_MM) Integer widthMm,
                       @NotNull @Positive @Max(MAX_MM) Integer heightMm, @NotNull @Positive Integer quantity,
                       @NotNull @Positive @DecimalMax(MAX_TOTAL_WEIGHT_KG) @Digits(integer = 7, fraction = 3)
                       BigDecimal totalWeightKg,
                       Boolean rotatable, Boolean fill) { }
}
