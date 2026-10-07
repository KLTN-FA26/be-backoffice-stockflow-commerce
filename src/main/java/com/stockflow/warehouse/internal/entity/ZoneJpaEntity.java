package com.stockflow.warehouse.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.util.UUID;

/**
 * JPA mapping of a zone (table {@code warehouse.zone}). Not the domain model.
 *
 * <p>An optional, purely logical grouping of shelves and areas - shown in its colour on the map and
 * used for zone picking. It carries no storage rule (docs module 06 §1).</p>
 */
@Entity
@Table(name = "zone", schema = "warehouse",
        uniqueConstraints = @UniqueConstraint(name = "uk_zone_warehouse_name",
                columnNames = {"warehouse_id", "name"}))
public class ZoneJpaEntity extends BaseEntity {

    @Column(name = "warehouse_id", nullable = false, updatable = false)
    private UUID warehouseId;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    /** {@code #RRGGBB}, upper-case hex. */
    @Column(name = "color", length = 7)
    private String color;

    protected ZoneJpaEntity() {
    }

    public ZoneJpaEntity(UUID id, UUID warehouseId, String name, String color) {
        super(id);
        this.warehouseId = warehouseId;
        this.name = name;
        this.color = color;
    }

    public UUID getWarehouseId() { return warehouseId; }
    public String getName() { return name; }
    public String getColor() { return color; }
}
