package com.stockflow.inventory.internal.domain;

/**
 * "May stock be put here?" — asked before stock is moved to a location, so a typo is a 404 and a
 * blocked bin is a 409, each with a real error code, rather than a foreign-key refusal from the
 * ledger insert or, worse, a move the ledger records into a place nobody may use (issue #67).
 *
 * <p>A port because the answer belongs to {@code warehouse}: a location's own status is narrowed by
 * its shelf's and its warehouse's (issue #18 D2), and only {@code warehouse :: api} computes that.</p>
 */
public interface LocationDirectory {

    /** What {@code warehouse} says about a location code, for a decision inventory makes. */
    enum LocationState {
        /** No location has this code. */
        UNKNOWN,
        /** It exists, but it, its shelf or its warehouse is BLOCKED, in MAINTENANCE or INACTIVE. */
        UNUSABLE,
        USABLE
    }

    LocationState stateOf(LocationId location);

    /** Whether the location is a PACKING area: where finished custom goods wait for their package. */
    boolean isPackingArea(LocationId location);

    /**
     * The locations among {@code locations} a picker may take stock from now (pickable and usable), each with its
     * warehouse id. Allocation chooses bins from these only (SCRUM-152, module 06 BR-12).
     */
    java.util.Map<String, java.util.UUID> pickable(java.util.Collection<String> locations);

    /** The warehouse a location belongs to; empty for an unknown code. */
    java.util.Optional<java.util.UUID> warehouseOf(LocationId location);

    /**
     * Whether the location is a QUARANTINE area: where goods that may not be sold wait apart from good ones - QC's
     * held and rejected goods (docs 03), damaged goods (docs 03 §5).
     */
    boolean isQuarantineArea(LocationId location);

    /** The usable QUARANTINE areas of a warehouse, by location code: where damaged goods can be put. */
    java.util.List<LocationId> quarantineAreas(java.util.UUID warehouseId);

    /** Whether the location is a bin on a shelf, as opposed to a floor area: where goods are stored for picking. */
    boolean isBin(LocationId location);
}
