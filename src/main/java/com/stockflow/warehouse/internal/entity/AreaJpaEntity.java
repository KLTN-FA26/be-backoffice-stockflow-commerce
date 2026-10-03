package com.stockflow.warehouse.internal.entity;

import com.stockflow.warehouse.internal.domain.AreaType;
import com.stockflow.warehouse.internal.domain.LocationStatus;
import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * JPA mapping of a floor area (table {@code warehouse.area}): space on the floor that is not a
 * shelf. Not the domain model.
 *
 * <p>Every type except {@code NON_STORAGE} holds stock (docs module 06 BR-11), so an area is a
 * location like a bin and carries a {@code locationCode} - {@code prefix-area}, e.g.
 * {@code HN-RCV01}. It has one {@code -} where a bin code has three, so the two can never
 * collide.</p>
 */
@Entity
@Table(name = "area", schema = "warehouse",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_area_warehouse_code",
                        columnNames = {"warehouse_id", "code"}),
                @UniqueConstraint(name = "uk_area_location_code", columnNames = "location_code")})
public class AreaJpaEntity extends BaseEntity {

    @Column(name = "warehouse_id", nullable = false, updatable = false)
    private UUID warehouseId;

    @Column(name = "zone_id")
    private UUID zoneId;

    @Column(name = "code", nullable = false, length = 20, updatable = false)
    private String code;

    @Column(name = "location_code", nullable = false, length = 64, updatable = false)
    private String locationCode;

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

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private LocationStatus status;

    protected AreaJpaEntity() {
    }

    public AreaJpaEntity(UUID id, UUID warehouseId, UUID zoneId, String code, String locationCode,
                         AreaType type, String name, BigDecimal x, BigDecimal y, BigDecimal width,
                         BigDecimal length, int rotation, boolean obstacle, LocationStatus status) {
        super(id);
        this.warehouseId = warehouseId;
        this.zoneId = zoneId;
        this.code = code;
        this.locationCode = locationCode;
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

    public UUID getWarehouseId() { return warehouseId; }
    public UUID getZoneId() { return zoneId; }
    public String getCode() { return code; }
    public String getLocationCode() { return locationCode; }
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
