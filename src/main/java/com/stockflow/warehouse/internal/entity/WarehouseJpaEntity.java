package com.stockflow.warehouse.internal.entity;

import com.stockflow.warehouse.internal.domain.MapUnit;
import com.stockflow.warehouse.internal.domain.WarehouseStatus;
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
 * JPA mapping of a warehouse and its map frame (table {@code warehouse.warehouse}). Not the domain
 * model.
 *
 * <p>{@code prefix} is the start of every location code and document number raised at this
 * warehouse, and {@code mapUnit} gives every coordinate on its map a meaning; neither changes once
 * the row exists (docs module 06 BR-13, trigger {@code tg_warehouse_immutable}).</p>
 *
 * <p><b>Expand period.</b> {@code V20260928000100} added the map columns next to the flat model's
 * {@code code}, {@code address_line} and {@code city}, which stay until contract
 * {@code db/pending/C2__contract_warehouse.sql} drops them. {@code code} is still {@code NOT NULL},
 * so this entity keeps writing it - with the prefix, which is what it meant - or no warehouse could
 * be inserted. {@code address_line} and {@code city} are nullable and left unmapped. Until C2 the
 * map columns are nullable too, but nothing creates a warehouse without them.</p>
 */
@Entity
@Table(name = "warehouse", schema = "warehouse",
        uniqueConstraints = @UniqueConstraint(name = "uk_warehouse_prefix", columnNames = "prefix"))
public class WarehouseJpaEntity extends BaseEntity {

    // TODO(C2): remove together with the column when contract C2 is activated (issue #18 D12).
    @Column(name = "code", nullable = false, length = 64, updatable = false)
    private String legacyCode;

    @Column(name = "prefix", nullable = false, length = 10, updatable = false)
    private String prefix;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "address", nullable = false, length = 500)
    private String address;

    /** Where returned parcels go, if not to {@link #address}. Used by shipping (docs module 09). */
    @Column(name = "return_address", length = 500)
    private String returnAddress;

    @Enumerated(EnumType.STRING)
    @Column(name = "map_unit", nullable = false, length = 8, updatable = false)
    private MapUnit mapUnit;

    @Column(name = "map_width", nullable = false, precision = 10, scale = 3)
    private BigDecimal mapWidth;

    @Column(name = "map_height", nullable = false, precision = 10, scale = 3)
    private BigDecimal mapHeight;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private WarehouseStatus status;

    protected WarehouseJpaEntity() {
    }

    public WarehouseJpaEntity(UUID id, String prefix, String name, String address,
                              String returnAddress, MapUnit mapUnit, BigDecimal mapWidth,
                              BigDecimal mapHeight, WarehouseStatus status) {
        super(id);
        this.legacyCode = prefix;
        this.prefix = prefix;
        this.name = name;
        this.address = address;
        this.returnAddress = returnAddress;
        this.mapUnit = mapUnit;
        this.mapWidth = mapWidth;
        this.mapHeight = mapHeight;
        this.status = status;
    }

    /** Everything a warehouse may change after registration; prefix and map unit never do. */
    public void apply(String name, String address, String returnAddress, BigDecimal mapWidth,
                      BigDecimal mapHeight, WarehouseStatus status) {
        this.name = name;
        this.address = address;
        this.returnAddress = returnAddress;
        this.mapWidth = mapWidth;
        this.mapHeight = mapHeight;
        this.status = status;
    }

    public String getPrefix() { return prefix; }
    public String getName() { return name; }
    public String getAddress() { return address; }
    public String getReturnAddress() { return returnAddress; }
    public MapUnit getMapUnit() { return mapUnit; }
    public BigDecimal getMapWidth() { return mapWidth; }
    public BigDecimal getMapHeight() { return mapHeight; }
    public WarehouseStatus getStatus() { return status; }
}
