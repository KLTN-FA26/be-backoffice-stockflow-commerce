package com.stockflow.inventory.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import com.stockflow.inventory.internal.domain.StockPackageStatus;
import com.stockflow.inventory.internal.domain.StockStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.Formula;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * JPA mapping of {@code inventory.stock_package}: the carton, which is the stock (ADR-0011; V20261020000100,
 * {@code counted_at} since V20261231000300, {@code stock_status}, {@code lot_id}, {@code received_at} and the
 * allocations since V20261231000600). <b>Not</b> the domain model.
 *
 * <p>Keeping this separate from {@code StockPackage} costs a mapper and buys three things: the aggregate stays free of
 * the no-args constructor and mutable field access that JPA demands and that would let anyone bypass its invariants; a
 * column rename never touches domain code; and the domain can be unit-tested with no persistence provider.</p>
 *
 * <p>What identifies a carton - its LPN, item, unit, nominal quantity and receipt line - is not updatable, as
 * {@code tg_stock_package_immutable} says. The lot and the FIFO date are written once, when the carton goes into
 * stock ({@code tg_stock_package_transition}). The carton's SKU and its lot number are not columns of it: they are
 * read through {@code inventory_items} and {@code stock_lot}.</p>
 */
@Entity
@Table(name = "stock_package", schema = "inventory")
public class StockPackageJpaEntity extends BaseEntity {

    @Column(name = "lpn", nullable = false, updatable = false, length = 30)
    private String lpn;

    @Column(name = "inventory_item_id", nullable = false, updatable = false)
    private UUID inventoryItemId;

    /** The item's SKU, read with the row; never written. A new entity carries the value its aggregate was made with. */
    @Formula("(select i.sku from inventory.inventory_items i where i.id = inventory_item_id)")
    private String sku;

    @Column(name = "uom_conversion_id", nullable = false, updatable = false)
    private UUID uomConversionId;

    @Column(name = "nominal_qty", nullable = false, updatable = false)
    private int nominalQty;

    @Column(name = "counted_at")
    private Instant countedAt;

    @Column(name = "location_code", nullable = false, length = 64)
    private String locationCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private StockPackageStatus status;

    /**
     * {@code EnumType.STRING}, never {@code ORDINAL}: with ordinals, inserting a value in the middle of the enum
     * silently reinterprets every row — quarantined stock becomes available. Null exactly while the carton is not in
     * stock ({@code ck_stock_package_stock_status}).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "stock_status", length = 32)
    private StockStatus stockStatus;

    @Column(name = "lot_id")
    private UUID lotId;

    /** The lot's number, read with the row; never written. */
    @Formula("(select l.lot_number from inventory.stock_lot l where l.id = lot_id)")
    private String lotNumber;

    @Column(name = "received_at")
    private Instant receivedAt;

    @Column(name = "goods_receipt_line_id", updatable = false)
    private UUID goodsReceiptLineId;

    @Column(name = "closed_at")
    private Instant closedAt;

    /**
     * The carton's allocations are part of the aggregate, so they are loaded and saved with it —
     * {@code cascade = ALL}, {@code orphanRemoval = true}, and no repository of their own. A hold made or closed is a
     * change of the carton, so it moves the carton's {@code @Version}.
     *
     * <p>{@code FetchType.LAZY} because most list reads never touch them, and {@code @BatchSize} to stop a list from
     * issuing one SELECT per carton when they do.</p>
     */
    @OneToMany(mappedBy = "stockPackage", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @BatchSize(size = 50)
    private List<ReservationJpaEntity> reservations = new ArrayList<>();

    /** Required by JPA. */
    protected StockPackageJpaEntity() {
    }

    @SuppressWarnings("java:S107")
    public StockPackageJpaEntity(UUID id, String lpn, UUID inventoryItemId, String sku, UUID uomConversionId,
                                 int nominalQty, UUID goodsReceiptLineId) {
        super(id);
        this.lpn = lpn;
        this.inventoryItemId = inventoryItemId;
        this.sku = sku;
        this.uomConversionId = uomConversionId;
        this.nominalQty = nominalQty;
        this.goodsReceiptLineId = goodsReceiptLineId;
    }

    /** Copies the carton's changing state onto the row. {@code lotNumber} only keeps the in-memory copy current. */
    @SuppressWarnings("java:S107")
    public void apply(Instant countedAt, String locationCode, StockPackageStatus status, StockStatus stockStatus,
                      UUID lotId, String lotNumber, Instant receivedAt, Instant closedAt) {
        this.countedAt = countedAt;
        this.locationCode = locationCode;
        this.status = status;
        this.stockStatus = stockStatus;
        this.lotId = lotId;
        this.lotNumber = lotNumber;
        this.receivedAt = receivedAt;
        this.closedAt = closedAt;
    }

    public void replaceReservations(List<ReservationJpaEntity> replacement) {
        this.reservations.clear();
        replacement.forEach(child -> {
            child.attachTo(this);
            this.reservations.add(child);
        });
    }

    public String getLpn() { return lpn; }
    public UUID getInventoryItemId() { return inventoryItemId; }
    public String getSku() { return sku; }
    public UUID getUomConversionId() { return uomConversionId; }
    public int getNominalQty() { return nominalQty; }
    public Instant getCountedAt() { return countedAt; }
    public String getLocationCode() { return locationCode; }
    public StockPackageStatus getStatus() { return status; }
    public StockStatus getStockStatus() { return stockStatus; }
    public UUID getLotId() { return lotId; }
    public String getLotNumber() { return lotNumber; }
    public Instant getReceivedAt() { return receivedAt; }
    public UUID getGoodsReceiptLineId() { return goodsReceiptLineId; }
    public Instant getClosedAt() { return closedAt; }
    public List<ReservationJpaEntity> getReservations() { return reservations; }
}
