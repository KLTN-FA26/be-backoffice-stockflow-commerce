package com.stockflow.warehouse.internal.domain;

import com.stockflow.common.domain.AggregateRoot;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A shelf with its levels, their bins and each bin's storage location - one aggregate, because the
 * rules need the whole tree: a bin lies inside its shelf, no two bins overlap on a level, bin codes
 * are unique per level and level numbers per shelf, resizing the shelf may not leave a bin outside,
 * and a pickable bin needs a pick face (BR-08, first half).
 *
 * <p>What a shelf cannot decide alone - inside the warehouse map (BR-06), clear of other shelves and
 * areas (BR-07), its zone in the same warehouse - the application service checks under the
 * warehouse row lock (issue #18 D4).</p>
 *
 * <p>Bounded (issue #18 D7): at most {@value #MAX_LEVELS} levels and
 * {@value BinGrid#MAX_BINS_PER_LEVEL} bins a level, since the tree is loaded and saved whole.</p>
 *
 * <p>Nothing here is ever removed. A bin leaves the layout by becoming {@code INACTIVE}; it then
 * no longer holds its place, and coming back needs the place free (issue #18 D3). Changing the
 * shelf's status does not touch its bins (D2).</p>
 */
public final class Shelf extends AggregateRoot {

    public static final int MAX_LEVELS = 20;
    static final int NAME_MAX_LENGTH = 200;
    static final int DESCRIPTION_MAX_LENGTH = 1000;

    private final UUID id;
    private final UUID warehouseId;
    private UUID zoneId;
    private final String code;
    private String name;
    private String description;
    private Footprint footprint;
    private boolean obstacle;
    private PickFaces pickFaces;
    private StorageClass defaultStorageClass;
    private LocationStatus status;
    private final List<ShelfLevel> levels;
    private final long version;

    /** Rehydration from storage. New shelves go through {@link #create}. */
    public Shelf(UUID id, UUID warehouseId, UUID zoneId, String code, String name, String description,
                 Footprint footprint, boolean obstacle, PickFaces pickFaces,
                 StorageClass defaultStorageClass, LocationStatus status, List<ShelfLevel> levels,
                 long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.warehouseId = Objects.requireNonNull(warehouseId, "warehouseId");
        this.zoneId = zoneId;
        this.code = Objects.requireNonNull(code, "code");
        this.name = name;
        this.description = description;
        this.footprint = Objects.requireNonNull(footprint, "footprint");
        this.obstacle = obstacle;
        this.pickFaces = Objects.requireNonNull(pickFaces, "pickFaces");
        this.defaultStorageClass = Objects.requireNonNull(defaultStorageClass, "defaultStorageClass");
        this.status = Objects.requireNonNull(status, "status");
        this.levels = new ArrayList<>(levels);
        this.version = version;
    }

    public static Shelf create(UUID id, UUID warehouseId, UUID zoneId, String code, String name,
                               String description, Footprint footprint, boolean obstacle,
                               PickFaces pickFaces, StorageClass defaultStorageClass) {
        requireShape(footprint, pickFaces, defaultStorageClass);
        return new Shelf(id, warehouseId, zoneId, CodePart.of(code, CodePart.CODE_MAX_LENGTH).value(),
                DomainChecks.requiredText("name", name, NAME_MAX_LENGTH),
                DomainChecks.optionalText("description", description, DESCRIPTION_MAX_LENGTH),
                footprint, obstacle, pickFaces, defaultStorageClass, LocationStatus.ACTIVE, List.of(), 0L);
    }

    /**
     * Everything but the code. A new size must still hold every bin on the layout; a new default
     * class reaches every bin without an override (issue #18 D9).
     *
     * @param expectedVersion the version the caller's edit was based on
     */
    public void update(UUID zoneId, String name, String description, Footprint footprint, boolean obstacle,
                       PickFaces pickFaces, StorageClass defaultStorageClass, long expectedVersion) {
        if (expectedVersion != version) {
            throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK,
                    "Shelf %s changed meanwhile (version %d, edit based on %d)".formatted(code, version, expectedVersion));
        }
        requireShape(footprint, pickFaces, defaultStorageClass);
        String newName = DomainChecks.requiredText("name", name, NAME_MAX_LENGTH);
        String newDescription = DomainChecks.optionalText("description", description, DESCRIPTION_MAX_LENGTH);
        for (ShelfLevel level : levels) {
            for (Bin bin : level.bins()) {
                if (bin.holdsPlace() && !bin.footprint().fitsWithin(footprint.width(), footprint.length())) {
                    throw new BusinessException(ErrorCode.LAYOUT_OUT_OF_BOUNDS,
                            "Shelf %s cannot shrink: bin %s on level %d would stick out"
                                    .formatted(code, bin.code(), level.levelIndex()));
                }
            }
        }
        if (!pickFaces.hasAny() && hasPickableBin()) {
            throw pickFaceRequired();
        }
        this.zoneId = zoneId;
        this.name = newName;
        this.description = newDescription;
        this.footprint = footprint;
        this.obstacle = obstacle;
        this.pickFaces = pickFaces;
        this.defaultStorageClass = defaultStorageClass;
        levels.forEach(level -> level.bins().stream()
                .filter(bin -> bin.details().storageClassOverride() == null)
                .forEach(bin -> bin.location().reclassify(defaultStorageClass)));
    }

    /** Bins keep their own status (issue #18 D2). */
    public void changeStatus(LocationStatus status) {
        if (status == null) {
            throw DomainChecks.invalid("A status is required");
        }
        this.status = status;
    }

    public ShelfLevel addLevel(UUID levelId, int levelIndex, LevelMeasures measures) {
        if (levelIndex < LocationCode.MIN_LEVEL || levelIndex > LocationCode.MAX_LEVEL) {
            throw DomainChecks.invalid("A level index is %d-%d, got %d"
                    .formatted(LocationCode.MIN_LEVEL, LocationCode.MAX_LEVEL, levelIndex));
        }
        if (levels.stream().anyMatch(level -> level.levelIndex() == levelIndex)) {
            throw new BusinessException(ErrorCode.SHELF_LEVEL_ALREADY_EXISTS,
                    "Shelf %s already has level %d".formatted(code, levelIndex));
        }
        if (levels.size() >= MAX_LEVELS) {
            throw new BusinessException(ErrorCode.SHELF_CAPACITY_EXCEEDED,
                    "Shelf %s already has the maximum of %d levels".formatted(code, MAX_LEVELS));
        }
        ShelfLevel level = new ShelfLevel(levelId, levelIndex, Objects.requireNonNull(measures, "measures"), List.of());
        levels.add(level);
        return level;
    }

    public void updateLevel(UUID levelId, LevelMeasures measures) {
        level(levelId).apply(Objects.requireNonNull(measures, "measures"));
    }

    /**
     * Adds a bin and its storage location, coded {@code prefix-shelf-level-bin} (BR-10).
     *
     * @param warehousePrefix the prefix of this shelf's warehouse - immutable, so safe to bake in
     */
    public Bin addBin(UUID levelId, UUID binId, UUID locationId, String code, BinDetails details,
                      LocationSettings settings, String warehousePrefix) {
        ShelfLevel level = level(levelId);
        String binCode = CodePart.of(code, CodePart.CODE_MAX_LENGTH).value();
        if (level.bins().stream().anyMatch(bin -> bin.code().equals(binCode))) {
            throw new BusinessException(ErrorCode.BIN_CODE_ALREADY_EXISTS,
                    "Level %d of shelf %s already has bin %s".formatted(level.levelIndex(), this.code, binCode));
        }
        if (level.bins().size() >= BinGrid.MAX_BINS_PER_LEVEL) {
            throw new BusinessException(ErrorCode.SHELF_CAPACITY_EXCEEDED,
                    "Level %d of shelf %s already has the maximum of %d bins"
                            .formatted(level.levelIndex(), this.code, BinGrid.MAX_BINS_PER_LEVEL));
        }
        requirePlaceable(level, details.footprint(), null);
        requirePickFaceFor(settings);

        LocationCode locationCode = LocationCode.ofBin(warehousePrefix, this.code, level.levelIndex(), binCode);
        StorageLocation location = StorageLocation.forBin(locationId, warehouseId, locationCode,
                storageClassOf(details), settings);
        Bin bin = new Bin(binId, binCode, details, location);
        level.add(bin);
        return bin;
    }

    /** Everything but the code. A bin on the layout must still fit and stay clear of its neighbours. */
    public void updateBin(UUID levelId, UUID binId, BinDetails details, LocationSettings settings) {
        ShelfLevel level = level(levelId);
        Bin bin = level.bin(binId);
        if (bin.holdsPlace()) {
            requirePlaceable(level, details.footprint(), binId);
        } else {
            requireInside(level, details.footprint());
        }
        requirePickFaceFor(settings);
        bin.apply(details);
        bin.location().apply(storageClassOf(details), settings);
    }

    /** Bringing a bin back from {@code INACTIVE} needs its place free again (issue #18 D3). */
    public void changeBinStatus(UUID levelId, UUID binId, LocationStatus status) {
        ShelfLevel level = level(levelId);
        Bin bin = level.bin(binId);
        if (!bin.holdsPlace() && status != null && status != LocationStatus.INACTIVE) {
            requireClear(level, bin.footprint(), binId);
        }
        bin.location().changeStatus(status);
    }

    public ShelfLevel level(UUID levelId) {
        return levels.stream().filter(level -> level.id().equals(levelId)).findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.SHELF_LEVEL_NOT_FOUND,
                        "Level %s is not on shelf %s".formatted(levelId, code)));
    }

    private void requirePlaceable(ShelfLevel level, Footprint candidate, UUID ignoredBinId) {
        requireInside(level, candidate);
        requireClear(level, candidate, ignoredBinId);
    }

    private void requireInside(ShelfLevel level, Footprint candidate) {
        if (!candidate.fitsWithin(footprint.width(), footprint.length())) {
            throw new BusinessException(ErrorCode.LAYOUT_OUT_OF_BOUNDS,
                    "A bin on level %d must lie inside shelf %s".formatted(level.levelIndex(), code));
        }
    }

    private static void requireClear(ShelfLevel level, Footprint candidate, UUID ignoredBinId) {
        level.bins().stream()
                .filter(Bin::holdsPlace)
                .filter(other -> !other.id().equals(ignoredBinId))
                .filter(other -> other.footprint().overlaps(candidate))
                .findFirst()
                .ifPresent(other -> {
                    throw new BusinessException(ErrorCode.LAYOUT_OVERLAP,
                            "Bin would overlap bin %s on level %d".formatted(other.code(), level.levelIndex()));
                });
    }

    private void requirePickFaceFor(LocationSettings settings) {
        if (settings.pickable() && !pickFaces.hasAny()) {
            throw pickFaceRequired();
        }
    }

    private boolean hasPickableBin() {
        return levels.stream().flatMap(level -> level.bins().stream()).anyMatch(bin -> bin.location().pickable());
    }

    private BusinessException pickFaceRequired() {
        return new BusinessException(ErrorCode.PICK_FACE_REQUIRED,
                "Shelf %s holds pickable bins, so it needs at least one pick face".formatted(code));
    }

    private StorageClass storageClassOf(BinDetails details) {
        return details.storageClassOverride() != null ? details.storageClassOverride() : defaultStorageClass;
    }

    private static void requireShape(Footprint footprint, PickFaces pickFaces, StorageClass defaultStorageClass) {
        if (footprint == null || pickFaces == null || defaultStorageClass == null) {
            throw DomainChecks.invalid("A shelf needs a footprint, pick faces and a default storage class");
        }
    }

    public UUID id() { return id; }
    public UUID warehouseId() { return warehouseId; }
    public UUID zoneId() { return zoneId; }
    public String code() { return code; }
    public String name() { return name; }
    public String description() { return description; }
    public Footprint footprint() { return footprint; }
    public boolean obstacle() { return obstacle; }
    public PickFaces pickFaces() { return pickFaces; }
    public StorageClass defaultStorageClass() { return defaultStorageClass; }
    public LocationStatus status() { return status; }
    public List<ShelfLevel> levels() { return Collections.unmodifiableList(levels); }
    public long version() { return version; }
}
