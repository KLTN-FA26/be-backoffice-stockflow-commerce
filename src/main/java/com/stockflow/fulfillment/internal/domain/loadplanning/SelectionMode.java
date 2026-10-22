package com.stockflow.fulfillment.internal.domain.loadplanning;

/**
 * How the planner picks vehicles (kltn-docs 09 §4.2 "Chế độ chọn xe"). Loading beyond the fleet is not a mode: the
 * vehicle counts cap every mode (BR-12) and what does not fit is reported unplaced.
 */
public enum SelectionMode {
    /** "Một loại": the one type that loads best, as many of it as needed (the original's {@code same_type}). */
    SINGLE_TYPE,
    /** "Ít xe nhất": types mixed so the plan uses the fewest vehicles (the original's {@code minimize}). */
    FEWEST_VEHICLES
}
