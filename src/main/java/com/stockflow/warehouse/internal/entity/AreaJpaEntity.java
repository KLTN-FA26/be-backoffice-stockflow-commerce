package com.stockflow.warehouse.internal.entity;

import com.stockflow.warehouse.internal.domain.AreaType;
import com.stockflow.warehouse.internal.domain.LocationStatus;
import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * JPA mapping of a floor area (table {@code warehouse.area}): space on the floor that is not a
 * shelf. Not the domain model.
 *
 * <p>Every type except {@code NON_STORAGE} holds stock (docs module 06 BR-11), so such an area owns
 * a {@link StorageLocationJpaEntity} of kind {@code AREA} whose code is {@code prefix-area}, e.g.
 * {@code HN-RCV01}. A {@code NON_STORAGE} area (office, aisle) has none ({@code ck_area_storage}).
 * Once set, the link never goes back to {@code null} ({@code tg_area_immutable}), so a storage area
 * can never become {@code NON_STORAGE}.</p>
 *
 * <p>Unlike a shelf, an area belongs to no zone: the table has no {@code zone_id}.</p>
 */
@Entity
@Table(name = "area", schema = "warehouse",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_area_warehouse_code",
                        columnNames = {"warehouse_id", "code"}),
                @UniqueConstraint(name = "uk_area_location", columnNames = "location_id")})
public class AreaJpaEntity extends BaseEntity {

    @Column(name = "warehouse_id", nullable = false, updatable = false)
    private UUID warehouseId;

    /**
     * {@code null} for a {@code NON_STORAGE} area. The owning side, so the location is inserted
     * before the area ({@code fk_area_location} is not deferrable). Updatable, because a
     * {@code NON_STORAGE} area that becomes a storage area gets its location then.
     */
    @OneToOne(fetch = FetchType.LAZY, cascade = {CascadeType.PERSIST, CascadeType.MERGE})
    @JoinColumn(name = "location_id", foreignKey = @ForeignKey(name = "fk_area_location"))
    private StorageLocationJpaEntity location;

    @Column(name = "code", nullable = false, length = 20, updatable = false)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 32)
    private AreaType type;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

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

    /** Always written together with the location's status, when there is a location. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private LocationStatus status;

    protected AreaJpaEntity() {
    }

    public AreaJpaEntity(UUID id, UUID warehouseId, StorageLocationJpaEntity location, String code,
                         AreaType type, String name, BigDecimal x, BigDecimal y, BigDecimal width,
                         BigDecimal length, int rotation, boolean obstacle, LocationStatus status) {
        super(id);
        this.warehouseId = warehouseId;
        this.location = location;
        this.code = code;
        this.type = type;
        this.name = name;
        this.x = x;
        this.y = y;
        this.width = width;
        this.length = length;
        this.rotation = rotation;
        this.obstacle = obstacle;
        this.status = status;
    }

    /** Everything about an area that may change; warehouse and code never do (BR-13). */
    public void apply(AreaType type, String name, BigDecimal x, BigDecimal y, BigDecimal width, BigDecimal length,
                      int rotation, boolean obstacle, LocationStatus status) {
        this.type = type;
        this.name = name;
        this.x = x;
        this.y = y;
        this.width = width;
        this.length = length;
        this.rotation = rotation;
        this.obstacle = obstacle;
        this.status = status;
    }

    /**
     * Gives a {@code NON_STORAGE} area that becomes a storage area its location (issue #18 D10). Once
     * only: the link never changes or goes back to {@code null} ({@code tg_area_immutable}).
     */
    public void attachLocation(StorageLocationJpaEntity location) {
        if (this.location != null) {
            throw new IllegalStateException("Area " + code + " already has a storage location");
        }
        this.location = location;
    }

    public UUID getWarehouseId() { return warehouseId; }
    public StorageLocationJpaEntity getLocation() { return location; }
    public String getCode() { return code; }
    public AreaType getType() { return type; }
    public String getName() { return name; }
    public BigDecimal getX() { return x; }
    public BigDecimal getY() { return y; }
    public BigDecimal getWidth() { return width; }
    public BigDecimal getLength() { return length; }
    public int getRotation() { return rotation; }
    public boolean isObstacle() { return obstacle; }
    public LocationStatus getStatus() { return status; }
}
