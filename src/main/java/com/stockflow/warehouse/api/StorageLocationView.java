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
 */
public record StorageLocationView(UUID id, Kind kind, UUID warehouseId, String locationCode,
                                  StorageClass storageClass, Status status, Status effectiveStatus,
                                  boolean usable, AreaType areaType) {

    /** A location whose area type is not asked for (a bin, or a caller that does not care). */
    public StorageLocationView(UUID id, Kind kind, UUID warehouseId, String locationCode, StorageClass storageClass,
                               Status status, Status effectiveStatus, boolean usable) {
        this(id, kind, warehouseId, locationCode, storageClass, status, effectiveStatus, usable, null);
    }

    /** What owns the location: a bin on a shelf level, a storage area on the floor, or a print subcontractor. */
    public enum Kind { BIN, AREA, SUBCONTRACTOR }

    /** The storage condition the location offers; a SKU goes only where its own class matches (BR-01). */
    public enum StorageClass { NORMAL, COLD, HAZMAT, FRAGILE, OVERSIZE }

    /** {@code INACTIVE} = taken off the layout; the location is kept, because history refers to its code. */
    public enum Status { ACTIVE, BLOCKED, MAINTENANCE, INACTIVE }

    /** What an area is for; null for a bin. Production issues blanks only to a {@code PRODUCTION} area. */
    public enum AreaType { RECEIVING, QUALITY_CONTROL, QUARANTINE, PACKING, DISPATCH, OVERFLOW, PRODUCTION, NON_STORAGE }
}
