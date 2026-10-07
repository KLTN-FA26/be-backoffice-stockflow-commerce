package com.stockflow.warehouse.internal.entity;

import com.stockflow.warehouse.internal.domain.BinType;
import com.stockflow.warehouse.internal.domain.StorageClass;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * JPA mapping of a bin (table {@code warehouse.bin}): the smallest storage slot on a shelf level.
 * A child of {@link ShelfLevelJpaEntity}, written through the shelf.
 *
 * <p>{@code code} is local - unique within its level, what an admin types and the map shows. What
 * stock, scanners and other modules see is the bin's {@link StorageLocationJpaEntity}: its global
 * {@code locationCode} ({@code prefix-shelf-level-bin}), status, storage class and capacity live
 * there, not here. Every bin owns exactly one location, of kind {@code BIN}, in its own warehouse,
 * with the code assembled from this bin's path - the database checks all of it
 * ({@code tg_bin_location}).</p>
 *
 * <p>Coordinates are relative to the shelf's own frame, so moving or rotating the shelf never
 * touches its bins.</p>
 *
 * <p>The table has {@code version} and audit columns; they are left unmapped (they have defaults).
 * The shelf's version guards the tree - see {@link ShelfJpaEntity}.</p>
 */
@Entity
@Table(name = "bin", schema = "warehouse",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_bin_level_code", columnNames = {"level_id", "code"}),
                @UniqueConstraint(name = "uk_bin_location", columnNames = "location_id")})
public class BinJpaEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "level_id", nullable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_bin_level"))
    private ShelfLevelJpaEntity level;

    /**
     * The owning side of the link, so Hibernate inserts the location before the bin:
     * {@code fk_bin_location} is not deferrable, and the other order fails on the first bin.
     * {@code PERSIST} and {@code MERGE} only - a location outlives nothing, it is never removed.
     */
    @OneToOne(fetch = FetchType.LAZY, optional = false,
            cascade = {CascadeType.PERSIST, CascadeType.MERGE})
    @JoinColumn(name = "location_id", nullable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_bin_location"))
    private StorageLocationJpaEntity location;

    @Column(name = "code", nullable = false, length = 20, updatable = false)
    private String code;

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

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 32)
    private BinType type;

    /**
     * {@code null} = the shelf's default storage class applies. The class actually in force is
     * stored on the location ({@link StorageLocationJpaEntity#getStorageClass()}).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "storage_class_override", length = 32)
    private StorageClass storageClassOverride;

    protected BinJpaEntity() {
    }

    public BinJpaEntity(UUID id, StorageLocationJpaEntity location, String code, String description,
                        BigDecimal x, BigDecimal y, BigDecimal width, BigDecimal length,
                        int rotation, BinType type, StorageClass storageClassOverride) {
        this.id = id;
        this.location = location;
        this.code = code;
        this.description = description;
        this.x = x;
        this.y = y;
        this.width = width;
        this.length = length;
        this.rotation = rotation;
        this.type = type;
        this.storageClassOverride = storageClassOverride;
    }

    void attachTo(ShelfLevelJpaEntity parent) {
        this.level = parent;
    }

    public UUID getId() { return id; }
    public StorageLocationJpaEntity getLocation() { return location; }
    public String getCode() { return code; }
    public String getDescription() { return description; }
    public BigDecimal getX() { return x; }
    public BigDecimal getY() { return y; }
    public BigDecimal getWidth() { return width; }
    public BigDecimal getLength() { return length; }
    public int getRotation() { return rotation; }
    public BinType getType() { return type; }
    public StorageClass getStorageClassOverride() { return storageClassOverride; }
}
