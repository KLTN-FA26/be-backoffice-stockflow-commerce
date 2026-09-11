package com.stockflow.warehouse.internal.entity;

import com.stockflow.warehouse.internal.domain.LocationType;
import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.util.UUID;

/**
 * JPA mapping of a storage location (table {@code warehouse.location}). Not the domain model.
 *
 * <p>STARTER ENTITY. {@code code} is what inventory stores against each stock row. Unique per
 * warehouse. {@code warehouseId} is a same-schema reference.</p>
 */
@Entity
@Table(name = "location", schema = "warehouse",
        uniqueConstraints = @UniqueConstraint(name = "uk_location_warehouse_code",
                columnNames = {"warehouse_id", "code"}))
public class LocationJpaEntity extends BaseEntity {

    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;

    @Column(name = "code", nullable = false, length = 64)
    private String code;

    @Column(name = "zone", length = 32)
    private String zone;

    @Column(name = "aisle", length = 32)
    private String aisle;

    @Column(name = "rack", length = 32)
    private String rack;

    @Column(name = "level", length = 32)
    private String level;

    @Column(name = "bin", length = 32)
    private String bin;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 32)
    private LocationType type;

    @Column(name = "golden_zone", nullable = false)
    private boolean goldenZone;

    @Column(name = "active", nullable = false)
    private boolean active;

    protected LocationJpaEntity() {
    }

    public LocationJpaEntity(UUID id, UUID warehouseId, String code, String zone, String aisle,
                             String rack, String level, String bin, LocationType type,
                             boolean goldenZone, boolean active) {
        super(id);
        this.warehouseId = warehouseId;
        this.code = code;
        this.zone = zone;
        this.aisle = aisle;
        this.rack = rack;
        this.level = level;
        this.bin = bin;
        this.type = type;
        this.goldenZone = goldenZone;
        this.active = active;
    }

    public UUID getWarehouseId() { return warehouseId; }
    public String getCode() { return code; }
    public String getZone() { return zone; }
    public String getAisle() { return aisle; }
    public String getRack() { return rack; }
    public String getLevel() { return level; }
    public String getBin() { return bin; }
    public LocationType getType() { return type; }
    public boolean isGoldenZone() { return goldenZone; }
    public boolean isActive() { return active; }
}
