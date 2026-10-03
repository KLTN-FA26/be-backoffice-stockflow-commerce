package com.stockflow.warehouse.internal.entity;

import com.stockflow.warehouse.internal.domain.LocationStatus;
import com.stockflow.warehouse.internal.domain.StorageClass;
import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * JPA mapping of a shelf (table {@code warehouse.shelf}): the root of the Shelf aggregate, whose
 * levels and bins are loaded and saved with it. Not the domain model.
 *
 * <p>Only this row is versioned. {@link ShelfLevelJpaEntity} and {@link BinJpaEntity} have no
 * {@code @Version} and no repository: they are written through the shelf, whose version already
 * guards the whole tree ({@code docs/adding-a-module.md} §4.3).</p>
 *
 * <p><b>No {@code orphanRemoval}, and never replace the {@code levels} list wholesale.</b> Nothing in
 * this aggregate is ever deleted (a location leaves the layout by becoming {@code INACTIVE}), and
 * bins carry a unique {@code location_code}: clearing the list and re-adding would make Hibernate
 * flush the INSERTs before the DELETEs and trip {@code uk_bin_location_code}. The mapper updates
 * children in place by id and appends new ones - see issue #22.</p>
 *
 * <p>Coordinates are in the warehouse's map unit; {@code (x, y)} is the top-left corner of the
 * footprint after rotation, and {@code rotation} is a quarter turn.</p>
 */
@Entity
@Table(name = "shelf", schema = "warehouse",
        uniqueConstraints = @UniqueConstraint(name = "uk_shelf_warehouse_code",
                columnNames = {"warehouse_id", "code"}))
public class ShelfJpaEntity extends BaseEntity {

    @Column(name = "warehouse_id", nullable = false, updatable = false)
    private UUID warehouseId;

    @Column(name = "zone_id")
    private UUID zoneId;

    @Column(name = "code", nullable = false, length = 20, updatable = false)
    private String code;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "description", length = 1000)
    private String description;

    @Column(name = "x", nullable = false, precision = 10, scale = 3)
    private BigDecimal x;

    @Column(name = "y", nullable = false, precision = 10, scale = 3)
    private BigDecimal y;

    @Column(name = "width", nullable = false, precision = 10, scale = 3)
    private BigDecimal width;

    @Column(name = "length", nullable = false, precision = 10, scale = 3)
    private BigDecimal length;

    @Column(name = "rotation", nullable = false)
    private int rotation;

    @Column(name = "is_obstacle", nullable = false)
    private boolean obstacle;

    /** Pick faces, in the shelf's own frame: north is its top edge before rotation. */
    @Column(name = "pick_north", nullable = false)
    private boolean pickNorth;

    @Column(name = "pick_east", nullable = false)
    private boolean pickEast;

    @Column(name = "pick_south", nullable = false)
    private boolean pickSouth;

    @Column(name = "pick_west", nullable = false)
    private boolean pickWest;

    @Enumerated(EnumType.STRING)
    @Column(name = "default_storage_class", nullable = false, length = 32)
    private StorageClass defaultStorageClass;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private LocationStatus status;

    /**
     * {@code LAZY}; a caller that needs the tree fetches it per query
     * ({@code ShelfJpaRepository.findByIdWithLevels}). Only this collection is fetch-joined - the
     * bins below it come in by {@code @BatchSize}, because fetch-joining two nested lists is a
     * {@code MultipleBagFetchException}.
     *
     * <p>Cascade is {@code PERSIST} and {@code MERGE} only, never {@code ALL}: a cascaded
     * {@code REMOVE} would let {@code delete(shelf)} erase its levels and bins first and so slip past
     * {@code fk_shelf_level_shelf}, the database's last guard against hard-deleting locations whose
     * codes still appear in stock history.</p>
     */
    @OneToMany(mappedBy = "shelf", cascade = {CascadeType.PERSIST, CascadeType.MERGE},
            fetch = FetchType.LAZY)
    @OrderBy("levelIndex")
    private List<ShelfLevelJpaEntity> levels = new ArrayList<>();

    protected ShelfJpaEntity() {
    }

    public ShelfJpaEntity(UUID id, UUID warehouseId, UUID zoneId, String code, String name,
                          String description, BigDecimal x, BigDecimal y, BigDecimal width,
                          BigDecimal length, int rotation, boolean obstacle, boolean pickNorth,
                          boolean pickEast, boolean pickSouth, boolean pickWest,
                          StorageClass defaultStorageClass, LocationStatus status) {
        super(id);
        this.warehouseId = warehouseId;
        this.zoneId = zoneId;
        this.code = code;
        this.name = name;
        this.description = description;
        this.x = x;
        this.y = y;
        this.width = width;
        this.length = length;
        this.rotation = rotation;
        this.obstacle = obstacle;
        this.pickNorth = pickNorth;
        this.pickEast = pickEast;
        this.pickSouth = pickSouth;
        this.pickWest = pickWest;
        this.defaultStorageClass = defaultStorageClass;
        this.status = status;
    }

    /** Appends a level; it is inserted with the shelf on the next flush. */
    public void addLevel(ShelfLevelJpaEntity level) {
        level.attachTo(this);
        this.levels.add(level);
    }

    public UUID getWarehouseId() { return warehouseId; }
    public UUID getZoneId() { return zoneId; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public BigDecimal getX() { return x; }
    public BigDecimal getY() { return y; }
    public BigDecimal getWidth() { return width; }
    public BigDecimal getLength() { return length; }
    public int getRotation() { return rotation; }
    public boolean isObstacle() { return obstacle; }
    public boolean isPickNorth() { return pickNorth; }
    public boolean isPickEast() { return pickEast; }
    public boolean isPickSouth() { return pickSouth; }
    public boolean isPickWest() { return pickWest; }
    public StorageClass getDefaultStorageClass() { return defaultStorageClass; }
    public LocationStatus getStatus() { return status; }
    public List<ShelfLevelJpaEntity> getLevels() { return levels; }
}
