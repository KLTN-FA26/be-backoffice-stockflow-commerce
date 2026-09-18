package com.stockflow.warehouse.internal.entity;

import com.stockflow.warehouse.internal.domain.BinType;
import com.stockflow.warehouse.internal.domain.LocationStatus;
import com.stockflow.warehouse.internal.domain.StorageClass;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * JPA mapping of a bin (table {@code warehouse.bin}): the smallest storage slot on a shelf level.
 * A child of {@link ShelfLevelJpaEntity}, written through the shelf.
 *
 * <p>{@code code} is local - unique within its level, what an admin types and the map shows.
 * {@code locationCode} is global - {@code prefix-shelf-level-bin}, assembled once at creation, and
 * the exact string {@code inventory.stock_item.location_code} stores and a scanner reads (docs
 * module 06 BR-10). Both are immutable (BR-13), which is what makes storing the assembled string
 * safe.</p>
 *
 * <p>Coordinates are relative to the shelf's own frame, so moving or rotating the shelf never
 * touches its bins.</p>
 */
@Entity
@Table(name = "bin", schema = "warehouse",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_bin_level_code", columnNames = {"level_id", "code"}),
                @UniqueConstraint(name = "uk_bin_location_code", columnNames = "location_code")})
public class BinJpaEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "level_id", nullable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_bin_level"))
    private ShelfLevelJpaEntity level;

    @Column(name = "code", nullable = false, length = 20, updatable = false)
    private String code;

    @Column(name = "location_code", nullable = false, length = 64, updatable = false)
    private String locationCode;

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

    /** Maximum number of base units. {@code null} = not checked. */
    @Column(name = "capacity_units")
    private Integer capacityUnits;

    @Column(name = "is_pickable", nullable = false)
    private boolean pickable;

    @Column(name = "is_putaway_bin", nullable = false)
    private boolean putawayBin;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 32)
    private BinType type;

    /** {@code null} = the shelf's default storage class applies. */
    @Enumerated(EnumType.STRING)
    @Column(name = "storage_class_override", length = 32)
    private StorageClass storageClassOverride;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private LocationStatus status;

    protected BinJpaEntity() {
    }

    public BinJpaEntity(UUID id, String code, String locationCode, String description,
                        BigDecimal x, BigDecimal y, BigDecimal width, BigDecimal length,
                        int rotation, Integer capacityUnits, boolean pickable, boolean putawayBin,
                        BinType type, StorageClass storageClassOverride, LocationStatus status) {
        this.id = id;
        this.code = code;
        this.locationCode = locationCode;
        this.description = description;
        this.x = x;
        this.y = y;
        this.width = width;
        this.length = length;
        this.rotation = rotation;
        this.capacityUnits = capacityUnits;
        this.pickable = pickable;
        this.putawayBin = putawayBin;
        this.type = type;
        this.storageClassOverride = storageClassOverride;
        this.status = status;
    }

    void attachTo(ShelfLevelJpaEntity parent) {
        this.level = parent;
    }

    public UUID getId() { return id; }
    public String getCode() { return code; }
    public String getLocationCode() { return locationCode; }
    public String getDescription() { return description; }
    public BigDecimal getX() { return x; }
    public BigDecimal getY() { return y; }
    public BigDecimal getWidth() { return width; }
    public BigDecimal getLength() { return length; }
    public int getRotation() { return rotation; }
    public Integer getCapacityUnits() { return capacityUnits; }
    public boolean isPickable() { return pickable; }
    public boolean isPutawayBin() { return putawayBin; }
    public BinType getType() { return type; }
    public StorageClass getStorageClassOverride() { return storageClassOverride; }
    public LocationStatus getStatus() { return status; }
}
