package com.stockflow.fulfillment.internal.domain.loadplanning;

/**
 * How the planner picks vehicles (kltn-docs 09 §4.2 "Chế độ chọn xe"). The vehicle counts cap every mode (BR-12): what
 * does not fit the vehicles on offer is reported unplaced, whatever the mode.
 *
 * <p>The docs' sixth mode, "Chỉnh tay" (drag a carton on the 3D model, then recalculate one vehicle), is not a way to
 * pick vehicles before a calculation: it edits a calculated plan (09 §4.2 step 3, BR-11), see
 * {@code LoadPlans#editPlacements} and {@code LoadPlans#recalculateVehicle}.</p>
 *
 * <p>Load plans and order estimates store their mode, and the database takes only {@link #SINGLE_TYPE} and
 * {@link #FEWEST_VEHICLES} for them; the other modes are offered by the trial estimate (LP2-9, WF4-TRIAL-EST).</p>
 */
public enum SelectionMode {
    /** "Một loại": the one type that loads best, as many of it as needed (the original's {@code same_type}). */
    SINGLE_TYPE,
    /** "Ít xe nhất": types mixed so the plan uses the fewest vehicles (the original's {@code minimize}). */
    FEWEST_VEHICLES,
    /**
     * "Giữ nguyên dòng": every carton of one line rides in one vehicle (the original's {@code keep_line_together}). A
     * line is the cartons sharing a {@link CargoPackage#sourceRef()} - an estimate line, a row of the trial sheet - or
     * a kit group. A line too big for one vehicle loads what fits and the rest is reported unplaced (09 edge case
     * "Chế độ Giữ nguyên dòng").
     */
    KEEP_LINES,
    /**
     * "Ưu tiên + hàng chèn": the main cargo decides the vehicles, as {@link #SINGLE_TYPE} does; the cartons marked
     * {@link CargoPackage#fill()} only top up the space it leaves and never open a vehicle (the original's
     * {@code solve_two_pool} with its default {@code same_type}).
     */
    PRIORITY_FILL,
    /**
     * "Vượt sức chứa": load as much as fits the vehicles on offer, mixing every chosen type up to its count, and report
     * the rest (the original's {@code shave_to_fit}). The search is {@link #FEWEST_VEHICLES}'s: the docs' order of
     * merit puts the fewest cartons left first.
     */
    OVER_CAPACITY;

    /** Whether a load plan or an order estimate may store this mode (its database check). */
    public boolean storable() {
        return this == SINGLE_TYPE || this == FEWEST_VEHICLES;
    }

    /** Whether the search mixes the vehicle types (the fleet drive) rather than trying each type alone. */
    boolean mixesTypes() {
        return this == FEWEST_VEHICLES || this == OVER_CAPACITY;
    }
}
