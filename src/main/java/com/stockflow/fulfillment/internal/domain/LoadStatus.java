package com.stockflow.fulfillment.internal.domain;

/**
 * Lifecycle of a {@link DispatchLoad}: one handover to one carrier's vehicle (kltn-docs 09 §4.1 step 3, §4.2 step 5;
 * BE 03 §11; Jira SCRUM-392). Mapped {@code EnumType.STRING}.
 *
 * <pre>
 *   parcel:  (driver arrives) LOADING ─both sides sign─▶ DISPATCHED
 *   FTL:     PLANNED ─vehicle booked─▶ VEHICLE_BOOKED ─vehicle arrives─▶ LOADING ─▶ DISPATCHED   (G4-1 / G4-2b)
 *   any open status ─▶ CANCELLED (its parcels go back to the dispatch area, untouched)
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

    /** Shipments and parcels may still be put on it or taken off. */
    public boolean isOpen() {
        return this == PLANNED || this == VEHICLE_BOOKED || this == LOADING;
    }
}
