package com.stockflow.fulfillment.internal.domain;

/**
 * Why the picker could not take a line's carton (kltn-docs 07 §4.4 step 9: "báo thiếu kèm vị trí và lý do"). Either way
 * nothing is taken, the order goes on hold and the carton is counted blind (BR-09); what happens to a damaged carton - a
 * whole-carton quarantine - is the warehouse manager's (20 §4.8), not the picker's. Mapped {@code EnumType.STRING}
 * ({@code ck_pick_line_short_reason}).
 */
public enum ShortPickReason {
    /** The carton is not at the line's bin (moved, mislaid, or never there). */
    NOT_FOUND,
    /** The carton is there but not fit to ship. */
    DAMAGED
}
