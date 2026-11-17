package com.stockflow.warehouse.api;

import java.util.UUID;

/**
 * A place stock can sit - a bin or a storage area - as another module needs to see it before
 * writing a reference to it: {@code id} is what {@code warehouse.storage_location} foreign keys
 * point at, {@code locationCode} what a scanner reads.
 *
 * <p>Named {@code ...View} because the warehouse's own domain already has a {@code StorageLocation},
 * which is not this: that one belongs to a bin or area aggregate and can change it; this is a
 * read-only snapshot taken in the caller's transaction.</p>
 *
 * <p>The enums are declared here rather than reused from the domain, so that nothing in this
 * package reaches into {@code internal} ({@code ArchitectureTest.theApiPackageLeaksNothingInternal}).
 * Their constants are the domain's, one for one.</p>
 *
 * @param status          the location's own status, as an admin set it on the bin or area
 * @param effectiveStatus {@code status} narrowed by everything above the location: its warehouse,
 *                        and for a bin its shelf (issue #18 D2). A bin that is {@code ACTIVE} on a
 *                        shelf in {@code MAINTENANCE} has effective status {@code MAINTENANCE}
 * @param usable          {@code effectiveStatus == ACTIVE}: stock may be put into or taken from here.
 *                        Check this, not {@code status}
 * @param areaType        what the area is for; null for a bin
 * @param binSize         a bin's inside, which every carton moved into it must fit within (kltn-docs 06 BR-01);
 *                        null for an area, where size is not checked (11 BR-10)
 */
public record StorageLocationView(UUID id, Kind kind, UUID warehouseId, String locationCode, Status status,
                                  Status effectiveStatus, boolean usable, AreaType areaType, BinSize binSize) {

    /** A location whose area type and size are not asked for (a caller that does not care). */
    public StorageLocationView(UUID id, Kind kind, UUID warehouseId, String locationCode, Status status,
                               Status effectiveStatus, boolean usable) {
        this(id, kind, warehouseId, locationCode, status, effectiveStatus, usable, null, null);
    }

    /**
     * What owns the location: a bin on a shelf level, a storage area on the floor, or - legacy, since subcontracting
     * went with purchasing (4A-8) - a print subcontractor.
     */
    public enum Kind { BIN, AREA, SUBCONTRACTOR }

    /** {@code INACTIVE} = taken off the layout; the location is kept, because history refers to its code. */
    public enum Status { ACTIVE, BLOCKED, MAINTENANCE, INACTIVE }

    /**
     * What an area is for; null for a bin. Production issues blanks only to a {@code PRODUCTION} area. {@code STAGING}
     * holds cartons picked for an order that may not leave yet (kltn-docs 07 §1, 20 §4.9); there is no PACKING area any
     * more - picked cartons are checked and labelled in the {@code DISPATCH} area (06 BR-22, V20261113000600) - nor a
     * QUALITY_CONTROL one: returned goods and faults wait in {@code QUARANTINE} (team plan 4D-4, V20261114000200).
     * {@code OVERFLOW} takes the cartons a putaway finds no bin for - the warehouse is full or the carton fits no bin
     * (05 BR-13).
     */
    public enum AreaType { RECEIVING, QUARANTINE, DISPATCH, STAGING, OVERFLOW, PRODUCTION, NON_STORAGE }
}
