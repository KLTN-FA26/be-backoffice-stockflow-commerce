package com.stockflow.warehouse.internal.domain;

import com.stockflow.common.domain.AggregateRoot;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Floor space that is not a shelf: receiving, quarantine, packing, dispatch, overflow - each a place
 * stock can sit, with a storage location coded {@code prefix-area} (BR-10) - or an office or aisle,
 * {@code NON_STORAGE}, on the map only to be drawn and walked around.
 *
 * <p>The location is part of this aggregate (issue #18 D8): there is no other way to change it, and
 * its status is always the area's. Writing both keeps a scanner reading {@code storage_location}
 * from disagreeing with the map reading {@code area}.</p>
 *
 * <p><b>A storage area never becomes {@code NON_STORAGE}</b> (D10): that would drop its location,
 * and other modules hold foreign keys to it - which is why {@code tg_area_immutable} forbids
 * {@code location_id} going back to {@code null}. The other way round is allowed: an office turned
 * into overflow space gets a new location.</p>
 *
 * <p>What an area cannot decide alone - inside the map (BR-06), clear of shelves and other areas
 * (BR-07) - the application service checks under the warehouse row lock (D4).</p>
 */
public final class Area extends AggregateRoot {

    private final UUID id;
    private final UUID warehouseId;
    private final String code;
    private AreaType type;
    private String name;
    private Footprint footprint;
    private boolean obstacle;
    private LocationStatus status;
    private StorageLocation location;
    private final long version;

    /** Rehydration from storage. New areas go through {@link #create}. */
    public Area(UUID id, UUID warehouseId, String code, AreaType type, String name, Footprint footprint,
                boolean obstacle, LocationStatus status, StorageLocation location, long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.warehouseId = Objects.requireNonNull(warehouseId, "warehouseId");
        this.code = Objects.requireNonNull(code, "code");
        this.type = Objects.requireNonNull(type, "type");
        this.name = name;
        this.footprint = Objects.requireNonNull(footprint, "footprint");
        this.obstacle = obstacle;
        this.status = Objects.requireNonNull(status, "status");
        this.location = location;
        this.version = version;
    }

    /**
     * A new {@code ACTIVE} area; a storage area gets an {@code ACTIVE} location coded
     * {@code prefix-code}.
     *
     * @param warehousePrefix the prefix of the area's warehouse - immutable, so safe to bake in
     * @param newLocationId   asked only when the area holds stock
     */
    public static Area create(UUID id, UUID warehouseId, String code, AreaDetails details, String warehousePrefix,
                              Supplier<UUID> newLocationId) {
        Objects.requireNonNull(details, "details");
        Area area = new Area(id, warehouseId, CodePart.of(code, CodePart.CODE_MAX_LENGTH).value(), details.type(),
                details.name(), details.footprint(), details.obstacle(), LocationStatus.ACTIVE, null, 0L);
        area.applyLocation(details, warehousePrefix, newLocationId);
        return area;
    }

    /**
     * Everything but the code. {@code NON_STORAGE} to a storage type creates the location; a storage
     * type to {@code NON_STORAGE} is refused (issue #18 D10).
     *
     * @param expectedVersion the version the caller's edit was based on
     */
    public void update(AreaDetails details, long expectedVersion, String warehousePrefix,
                       Supplier<UUID> newLocationId) {
        Objects.requireNonNull(details, "details");
        if (expectedVersion != version) {
            throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK,
                    "Area %s changed meanwhile (version %d, edit based on %d)".formatted(code, version, expectedVersion));
        }
        if (location != null && !details.type().isStorage()) {
            throw new BusinessException(ErrorCode.AREA_TYPE_CHANGE_NOT_ALLOWED,
                    "Area %s holds stock as %s, so it cannot become %s".formatted(code, location.locationCode(),
                            details.type()));
        }
        this.type = details.type();
        this.name = details.name();
        this.footprint = details.footprint();
        this.obstacle = details.obstacle();
        applyLocation(details, warehousePrefix, newLocationId);
    }

    /** The location follows (issue #18 D8). */
    public void changeStatus(LocationStatus status) {
        if (status == null) {
            throw DomainChecks.invalid("A status is required");
        }
        this.status = status;
        if (location != null) {
            location.changeStatus(status);
        }
    }

    /** Whether the area occupies its place on the map - every status but {@code INACTIVE} (#18 D3). */
    public boolean holdsPlace() {
        return status != LocationStatus.INACTIVE;
    }

    private void applyLocation(AreaDetails details, String warehousePrefix, Supplier<UUID> newLocationId) {
        if (!details.type().isStorage()) {
            return;
        }
        if (location == null) {
            location = StorageLocation.forArea(newLocationId.get(), warehouseId,
                    LocationCode.ofArea(warehousePrefix, code), details.storageClass(), details.settings(), status);
        } else {
            location.apply(details.storageClass(), details.settings());
        }
    }

    public UUID id() { return id; }
    public UUID warehouseId() { return warehouseId; }
    public String code() { return code; }
    public AreaType type() { return type; }
    public String name() { return name; }
    public Footprint footprint() { return footprint; }
    public boolean obstacle() { return obstacle; }
    public LocationStatus status() { return status; }
    /** {@code null} for a {@code NON_STORAGE} area. */
    public StorageLocation location() { return location; }
    public long version() { return version; }
}
