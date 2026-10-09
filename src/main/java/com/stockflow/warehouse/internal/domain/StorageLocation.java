package com.stockflow.warehouse.internal.domain;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * A place stock can sit - a bin, or a storage area (table {@code warehouse.storage_location}).
 *
 * <p>Not an aggregate root: it is created with, and changed only through, the bin or area that owns
 * it (issue #18 D8). The database agrees - a location owned by nothing is refused at commit, and
 * the owner can never point at another location. It is still a row of its own because seven
 * tables in other modules hold a foreign key to it.</p>
 *
 * <p>{@code locationCode}, {@code kind} and {@code warehouseId} never change (BR-13). For a bin,
 * the status here <i>is</i> the bin's status.</p>
 */
public final class StorageLocation {

    private final UUID id;
    private final UUID warehouseId;
    private final StorageLocationKind kind;
    private final String locationCode;
    private StorageClass storageClass;
    private LocationSettings settings;
    private LocationStatus status;

    /** Rehydration from storage. New locations come from {@link #forBin} or {@link #forArea}. */
    public StorageLocation(UUID id, UUID warehouseId, StorageLocationKind kind, String locationCode,
                           StorageClass storageClass, LocationSettings settings, LocationStatus status) {
        this.id = Objects.requireNonNull(id, "id");
        this.warehouseId = Objects.requireNonNull(warehouseId, "warehouseId");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.locationCode = Objects.requireNonNull(locationCode, "locationCode");
        this.storageClass = Objects.requireNonNull(storageClass, "storageClass");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.status = Objects.requireNonNull(status, "status");
    }

    static StorageLocation forBin(UUID id, UUID warehouseId, LocationCode code, StorageClass storageClass,
                                  LocationSettings settings) {
        return new StorageLocation(id, warehouseId, StorageLocationKind.BIN, code.value(), storageClass,
                settings, LocationStatus.ACTIVE);
    }

    /**
     * @param status the area's status, which the location always shares (issue #18 D8) - not
     *               necessarily {@code ACTIVE}: a {@code NON_STORAGE} area that becomes a storage area
     *               gets its location in whatever status it is in
     */
    static StorageLocation forArea(UUID id, UUID warehouseId, LocationCode code, StorageClass storageClass,
                                   LocationSettings settings, LocationStatus status) {
        return new StorageLocation(id, warehouseId, StorageLocationKind.AREA, code.value(), storageClass,
                settings, status);
    }

    void apply(StorageClass storageClass, LocationSettings settings) {
        this.storageClass = Objects.requireNonNull(storageClass, "storageClass");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    void reclassify(StorageClass storageClass) {
        this.storageClass = Objects.requireNonNull(storageClass, "storageClass");
    }

    void changeStatus(LocationStatus status) {
        if (status == null) {
            throw DomainChecks.invalid("A status is required");
        }
        this.status = status;
    }

    /** {@code INACTIVE} = taken off the layout; it no longer occupies its place (issue #18 D3). */
    public boolean isInactive() {
        return status == LocationStatus.INACTIVE;
    }

    public UUID id() { return id; }
    public UUID warehouseId() { return warehouseId; }
    public StorageLocationKind kind() { return kind; }
    public String locationCode() { return locationCode; }
    public StorageClass storageClass() { return storageClass; }
    public LocationSettings settings() { return settings; }
    public Integer capacityUnits() { return settings.capacityUnits(); }
    public BigDecimal maxWeight() { return settings.maxWeight(); }
    public boolean pickable() { return settings.pickable(); }
    public boolean putawayTarget() { return settings.putawayTarget(); }
    public LocationStatus status() { return status; }
}
