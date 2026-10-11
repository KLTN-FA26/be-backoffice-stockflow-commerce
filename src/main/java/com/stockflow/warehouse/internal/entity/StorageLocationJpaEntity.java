package com.stockflow.warehouse.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import com.stockflow.warehouse.internal.domain.LocationStatus;
import com.stockflow.warehouse.internal.domain.StorageClass;
import com.stockflow.warehouse.internal.domain.StorageLocationKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * JPA mapping of a storage location (table {@code warehouse.storage_location}): one row for every
 * place stock can sit, whether a bin or a storage area. Not the domain model.
 *
 * <p>This is the row other modules point at - goods receipt lines, QC inspections and move tasks
 * by id, stock movements, adjustments, cycle counts and pick lines by {@code locationCode} - so a
 * location is never deleted, only made {@code INACTIVE}.</p>
 *
 * <p>It has no repository of its own for writing: a location is created and changed through the
 * bin or area that owns it ({@link BinJpaEntity#getLocation()}, {@link AreaJpaEntity#getLocation()}),
 * which holds the foreign key and cascades the insert, so the location row always reaches the
 * database before its owner's row does. The database refuses a location still owned by nothing at
 * commit ({@code tg_storage_location_owned}, a deferred trigger).</p>
 *
 * <p>{@code locationCode}, {@code kind} and {@code warehouseId} never change once written (BR-13,
 * trigger {@code tg_storage_location_immutable}); the code is assembled from codes that are
 * themselves immutable, which is what makes storing it safe.</p>
 */
@Entity
@Table(name = "storage_location", schema = "warehouse")
public class StorageLocationJpaEntity extends BaseEntity {

    @Column(name = "warehouse_id", nullable = false, updatable = false)
    private UUID warehouseId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 8, updatable = false)
    private StorageLocationKind kind;

    /** {@code prefix-shelf-level-bin} or {@code prefix-area}; what a scanner reads (BR-10). */
    @Column(name = "location_code", nullable = false, length = 64, updatable = false)
    private String locationCode;

    /**
     * The resolved class, never {@code null}: for a bin, its override or else its shelf's default.
     * Stored rather than derived because putaway filters on it ({@code ix_storage_location_putaway}).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "storage_class", nullable = false, length = 32)
    private StorageClass storageClass;

    /** Maximum number of base units. {@code null} = not checked. */
    @Column(name = "capacity_units")
    private Integer capacityUnits;

    /** Load limit in kilograms. {@code null} = not checked. */
    @Column(name = "max_weight", precision = 10, scale = 3)
    private BigDecimal maxWeight;

    @Column(name = "is_pickable", nullable = false)
    private boolean pickable;

    @Column(name = "is_putaway_target", nullable = false)
    private boolean putawayTarget;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private LocationStatus status;

    protected StorageLocationJpaEntity() {
    }

    public StorageLocationJpaEntity(UUID id, UUID warehouseId, StorageLocationKind kind,
                                    String locationCode, StorageClass storageClass,
                                    Integer capacityUnits, BigDecimal maxWeight, boolean pickable,
                                    boolean putawayTarget, LocationStatus status) {
        super(id);
        this.warehouseId = warehouseId;
        this.kind = kind;
        this.locationCode = locationCode;
        this.storageClass = storageClass;
        this.capacityUnits = capacityUnits;
        this.maxWeight = maxWeight;
        this.pickable = pickable;
        this.putawayTarget = putawayTarget;
        this.status = status;
    }

    /** Everything about a location that may change; warehouse, kind and code never do (BR-13). */
    public void apply(StorageClass storageClass, Integer capacityUnits, BigDecimal maxWeight, boolean pickable,
                      boolean putawayTarget, LocationStatus status) {
        this.storageClass = storageClass;
        this.capacityUnits = capacityUnits;
        this.maxWeight = maxWeight;
        this.pickable = pickable;
        this.putawayTarget = putawayTarget;
        this.status = status;
    }

    public UUID getWarehouseId() { return warehouseId; }
    public StorageLocationKind getKind() { return kind; }
    public String getLocationCode() { return locationCode; }
    public StorageClass getStorageClass() { return storageClass; }
    public Integer getCapacityUnits() { return capacityUnits; }
    public BigDecimal getMaxWeight() { return maxWeight; }
    public boolean isPickable() { return pickable; }
    public boolean isPutawayTarget() { return putawayTarget; }
    public LocationStatus getStatus() { return status; }
}
