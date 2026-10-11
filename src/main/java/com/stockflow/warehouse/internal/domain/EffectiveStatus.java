package com.stockflow.warehouse.internal.domain;

import java.util.Objects;

/**
 * The status a place actually has once everything above it is taken into account (issue #18 D2).
 *
 * <p>Statuses are never cascaded on write: deactivating a warehouse or putting a shelf into
 * maintenance leaves every bin's own status alone, so turning the shelf back on returns each bin
 * to exactly what it was. The price is that "can stock go here?" is not a column - it is computed
 * here, every time it is read:</p>
 * <ul>
 *   <li>a bin: the narrowest of its warehouse, its shelf and its storage location;</li>
 *   <li>a shelf: the narrowest of its warehouse and itself;</li>
 *   <li>an area: the narrowest of its warehouse and itself (its location always shares its status,
 *       D8).</li>
 * </ul>
 *
 * <p>"Narrowest" ranks {@code INACTIVE} (off the layout) above {@code MAINTENANCE} (physically out
 * of use) above {@code BLOCKED} (held back, e.g. during a count) above {@code ACTIVE}. Only the
 * ranking matters to callers that show why a place is unusable; {@link #isUsable} is the same
 * answer whichever of the three wins. A warehouse has no maintenance state, so an inactive one
 * makes everything in it {@code INACTIVE}.</p>
 */
public final class EffectiveStatus {

    private EffectiveStatus() {
    }

    public static LocationStatus ofBin(WarehouseStatus warehouse, LocationStatus shelf, LocationStatus location) {
        return narrowest(ofShelf(warehouse, shelf), location);
    }

    public static LocationStatus ofShelf(WarehouseStatus warehouse, LocationStatus shelf) {
        return narrowest(of(warehouse), shelf);
    }

    public static LocationStatus ofArea(WarehouseStatus warehouse, LocationStatus area) {
        return narrowest(of(warehouse), area);
    }

    /** Stock may be put into or taken from the place. */
    public static boolean isUsable(LocationStatus effective) {
        return effective == LocationStatus.ACTIVE;
    }

    private static LocationStatus of(WarehouseStatus warehouse) {
        return Objects.requireNonNull(warehouse, "warehouse") == WarehouseStatus.ACTIVE
                ? LocationStatus.ACTIVE
                : LocationStatus.INACTIVE;
    }

    private static LocationStatus narrowest(LocationStatus a, LocationStatus b) {
        return rank(b) > rank(a) ? b : a;
    }

    /** Spelled out rather than {@code ordinal()}: reordering the enum must not change the answer. */
    private static int rank(LocationStatus status) {
        return switch (Objects.requireNonNull(status, "status")) {
            case ACTIVE -> 0;
            case BLOCKED -> 1;
            case MAINTENANCE -> 2;
            case INACTIVE -> 3;
        };
    }
}
