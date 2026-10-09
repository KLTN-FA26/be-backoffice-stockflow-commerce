package com.stockflow.warehouse.internal.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * The smallest storage slot on a shelf level. A child of {@link Shelf}: created, changed and
 * checked only through it, because whether a bin fits depends on its shelf and its neighbours.
 *
 * <p>{@code code} is local to the level and never changes; the global code, status, class and
 * capacity live on its {@link StorageLocation}.</p>
 */
public final class Bin {

    private final UUID id;
    private final String code;
    private BinDetails details;
    private final StorageLocation location;

    public Bin(UUID id, String code, BinDetails details, StorageLocation location) {
        this.id = Objects.requireNonNull(id, "id");
        this.code = Objects.requireNonNull(code, "code");
        this.details = Objects.requireNonNull(details, "details");
        this.location = Objects.requireNonNull(location, "location");
    }

    void apply(BinDetails details) {
        this.details = Objects.requireNonNull(details, "details");
    }

    /** Whether the bin occupies its place on the level - every status but {@code INACTIVE}. */
    boolean holdsPlace() {
        return !location.isInactive();
    }

    public UUID id() { return id; }
    public String code() { return code; }
    public BinDetails details() { return details; }
    public Footprint footprint() { return details.footprint(); }
    public StorageLocation location() { return location; }
}
