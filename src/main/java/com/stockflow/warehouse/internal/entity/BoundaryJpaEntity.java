package com.stockflow.warehouse.internal.entity;

import com.stockflow.warehouse.internal.domain.BoundaryType;
import com.stockflow.warehouse.internal.domain.DoorStatus;
import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * JPA mapping of a wall or door segment on the warehouse map (table {@code warehouse.boundary}).
 * Not the domain model.
 *
 * <p>A wall is never passable and has no open/closed state; a door always has one
 * ({@code ck_boundary_kind}). Walking distance for slotting and pick routes (docs module 06 BR-09)
 * will only cross doors that are passable and {@code OPEN}.</p>
 */
@Entity
@Table(name = "boundary", schema = "warehouse",
        indexes = @Index(name = "ix_boundary_warehouse", columnList = "warehouse_id"))
public class BoundaryJpaEntity extends BaseEntity {

    @Column(name = "warehouse_id", nullable = false, updatable = false)
    private UUID warehouseId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 32)
    private BoundaryType type;

    @Column(name = "start_x", nullable = false, precision = 10, scale = 3)
    private BigDecimal startX;

    @Column(name = "start_y", nullable = false, precision = 10, scale = 3)
    private BigDecimal startY;

    @Column(name = "end_x", nullable = false, precision = 10, scale = 3)
    private BigDecimal endX;

    @Column(name = "end_y", nullable = false, precision = 10, scale = 3)
    private BigDecimal endY;

    @Column(name = "is_passable", nullable = false)
    private boolean passable;

    /** Doors only; always {@code null} for a wall. */
    @Enumerated(EnumType.STRING)
    @Column(name = "operational_status", length = 32)
    private DoorStatus operationalStatus;

    protected BoundaryJpaEntity() {
    }

    public BoundaryJpaEntity(UUID id, UUID warehouseId, BoundaryType type, BigDecimal startX,
                             BigDecimal startY, BigDecimal endX, BigDecimal endY, boolean passable,
                             DoorStatus operationalStatus) {
        super(id);
        this.warehouseId = warehouseId;
        this.type = type;
        this.startX = startX;
        this.startY = startY;
        this.endX = endX;
        this.endY = endY;
        this.passable = passable;
        this.operationalStatus = operationalStatus;
    }

    public UUID getWarehouseId() { return warehouseId; }
    public BoundaryType getType() { return type; }
    public BigDecimal getStartX() { return startX; }
    public BigDecimal getStartY() { return startY; }
    public BigDecimal getEndX() { return endX; }
    public BigDecimal getEndY() { return endY; }
    public boolean isPassable() { return passable; }
    public DoorStatus getOperationalStatus() { return operationalStatus; }
}
