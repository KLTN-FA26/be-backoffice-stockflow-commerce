package com.stockflow.inventory.internal.entity;

import com.stockflow.inventory.internal.domain.StockStatus;
import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * JPA mapping of a stock item row. <b>Not</b> the domain model.
 *
 * <p>Keeping this separate from {@code StockItem} costs a mapper class and buys three things: the
 * aggregate stays free of a no-args constructor and mutable field access that JPA demands and that
 * would let anyone bypass its invariants; a column rename never touches domain code; and the
 * domain can be unit-tested with no persistence provider on the classpath.</p>
 *
 * <p><b>Schema, not database.</b> {@code schema = "inventory"} inside the one
 * {@code stockflow} database. Every module gets its own schema, which keeps ownership obvious and
 * makes "who writes this table" answerable, while a single connection pool and a single
 * transaction still cover the whole request.</p>
 *
 * <p><b>The unique constraint is the real guard against duplicate stock rows.</b> Application-level
 * "check then insert" loses a race; the database does not.</p>
 */
@Entity
@Table(
        name = "stock_item",
        schema = "inventory",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_stock_item_sku_location_lot",
                columnNames = {"sku", "location_code", "lot_number"}))
public class StockItemJpaEntity extends BaseEntity {

    @Column(name = "sku", nullable = false, length = 64)
    private String sku;

    @Column(name = "location_code", nullable = false, length = 64)
    private String locationCode;

    /** Nullable: not every product is lot-tracked. Part of the unique key, so NULLs matter here. */
    @Column(name = "lot_number", length = 64)
    private String lotNumber;

    @Column(name = "expiry_date")
    private LocalDate expiryDate;

    @Column(name = "on_hand", nullable = false)
    private int onHand;

    /**
     * Denormalised copy of {@code sum(active reservations)}.
     *
     * <p>Redundant against the child rows, and kept anyway: the catalog's ATP query runs on every
     * product page and must not aggregate a child table to answer it. The mapper recomputes this
     * from the aggregate on every save, so it can never drift.</p>
     */
    @Column(name = "reserved", nullable = false)
    private int reserved;

    /**
     * {@code EnumType.STRING}, never {@code ORDINAL}. With ordinals, inserting a value in the
     * middle of the enum silently reinterprets every existing row — quarantined stock becomes
     * available. The cost is a few bytes per row.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private StockStatus status;

    /**
     * Reservations are part of the aggregate, so they are loaded and saved with it —
     * {@code cascade = ALL}, {@code orphanRemoval = true}, and no repository of their own.
     *
     * <p>{@code FetchType.LAZY} because the ATP read path never touches them, and
     * {@code @BatchSize} to stop the sweeper's list query from issuing one SELECT per item.</p>
     */
    @OneToMany(mappedBy = "stockItem", cascade = CascadeType.ALL,
            orphanRemoval = true, fetch = FetchType.LAZY)
    @org.hibernate.annotations.BatchSize(size = 50)
    private List<ReservationJpaEntity> reservations = new ArrayList<>();

    /** Required by JPA. Not for application code — the mapper uses the full constructor. */
    protected StockItemJpaEntity() {
    }

    public StockItemJpaEntity(UUID id, String sku, String locationCode, String lotNumber,
                       LocalDate expiryDate, int onHand, int reserved, StockStatus status) {
        super(id);
        this.sku = sku;
        this.locationCode = locationCode;
        this.lotNumber = lotNumber;
        this.expiryDate = expiryDate;
        this.onHand = onHand;
        this.reserved = reserved;
        this.status = status;
    }

    public void replaceReservations(List<ReservationJpaEntity> replacement) {
        this.reservations.clear();
        replacement.forEach(child -> {
            child.attachTo(this);
            this.reservations.add(child);
        });
    }

    public void apply(int onHand, int reserved, StockStatus status) {
        this.onHand = onHand;
        this.reserved = reserved;
        this.status = status;
    }

    public String getSku() { return sku; }
    public String getLocationCode() { return locationCode; }
    public String getLotNumber() { return lotNumber; }
    public LocalDate getExpiryDate() { return expiryDate; }
    public int getOnHand() { return onHand; }
    public int getReserved() { return reserved; }
    public StockStatus getStatus() { return status; }
    public List<ReservationJpaEntity> getReservations() { return reservations; }
}
