package com.stockflow.warehouse.internal.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * JPA mapping of one level of a shelf (table {@code warehouse.shelf_level}). A child of
 * {@link ShelfJpaEntity} - never loaded or saved on its own, hence a plain {@code @Id} and no
 * version.
 *
 * <p>{@code levelIndex} is part of every bin's location code, so it never changes and levels are
 * never renumbered.</p>
 */
@Entity
@Table(name = "shelf_level", schema = "warehouse",
        uniqueConstraints = @UniqueConstraint(name = "uk_shelf_level_shelf_index",
                columnNames = {"shelf_id", "level_index"}))
public class ShelfLevelJpaEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "shelf_id", nullable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_shelf_level_shelf"))
    private ShelfJpaEntity shelf;

    @Column(name = "level_index", nullable = false, updatable = false)
    private int levelIndex;

    /** Height of the level floor, in the map unit. */
    @Column(name = "elevation", precision = 10, scale = 3)
    private BigDecimal elevation;

    /** Clear height, in the map unit. {@code null} = not checked. */
    @Column(name = "usable_height", precision = 10, scale = 3)
    private BigDecimal usableHeight;

    /** Load limit in kilograms. {@code null} = not checked. */
    @Column(name = "max_weight", precision = 10, scale = 3)
    private BigDecimal maxWeight;

    /**
     * {@code @BatchSize}: loading a shelf's levels then touching their bins costs one query per 50
     * levels instead of one per level. Cascade excludes {@code REMOVE} for the reason given on
     * {@code ShelfJpaEntity.levels}.
     */
    @OneToMany(mappedBy = "level", cascade = {CascadeType.PERSIST, CascadeType.MERGE},
            fetch = FetchType.LAZY)
    @org.hibernate.annotations.BatchSize(size = 50)
    @OrderBy("code")
    private List<BinJpaEntity> bins = new ArrayList<>();

    protected ShelfLevelJpaEntity() {
    }

    public ShelfLevelJpaEntity(UUID id, int levelIndex, BigDecimal elevation,
                               BigDecimal usableHeight, BigDecimal maxWeight) {
        this.id = id;
        this.levelIndex = levelIndex;
        this.elevation = elevation;
        this.usableHeight = usableHeight;
        this.maxWeight = maxWeight;
    }

    void attachTo(ShelfJpaEntity parent) {
        this.shelf = parent;
    }

    /** Appends a bin; it is inserted with the shelf on the next flush. */
    public void addBin(BinJpaEntity bin) {
        bin.attachTo(this);
        this.bins.add(bin);
    }

    public UUID getId() { return id; }
    public int getLevelIndex() { return levelIndex; }
    public BigDecimal getElevation() { return elevation; }
    public BigDecimal getUsableHeight() { return usableHeight; }
    public BigDecimal getMaxWeight() { return maxWeight; }
    public List<BinJpaEntity> getBins() { return bins; }
}
