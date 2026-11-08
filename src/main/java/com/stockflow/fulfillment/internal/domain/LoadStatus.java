package com.stockflow.fulfillment.internal.domain;

/**
 * Lifecycle of a {@link DispatchLoad}: one load plan's vehicle handed over to one carrier (kltn-docs 09 §4.2 step 5;
 * BE 03 §11; Jira SCRUM-392). Mapped {@code EnumType.STRING}. Loads of the parcel shipping that was removed
 * (V20261108000400) are only ever DISPATCHED or CANCELLED.
 *
 * <pre>
 *   PLANNED ─vehicle booked─▶ VEHICLE_BOOKED ─vehicle arrives─▶ LOADING ─both sides sign─▶ DISPATCHED   (G4-1 / G4-2b)
 *   any open status ─▶ CANCELLED (plan reopened or cancelled, nothing left on it: its parcels go back untouched)
 *   LOADING ─controlled cancel (SCRUM-474)─▶ CANCELLED, its scanned parcels locked (pending unloads) until scanned off;
 *             a new PLANNED load of the same plan vehicle takes its place
 * </pre>
 */
public enum LoadStatus {
    PLANNED,
    VEHICLE_BOOKED,
    LOADING,
    DISPATCHED,
    CANCELLED;

    /** Its plan may still change what it carries, and parcels may be scanned on or off. */
    public boolean isOpen() {
        return this == PLANNED || this == VEHICLE_BOOKED || this == LOADING;
    }
}
