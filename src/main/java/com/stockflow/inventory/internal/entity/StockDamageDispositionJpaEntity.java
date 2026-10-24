package com.stockflow.inventory.internal.entity;

import com.stockflow.inventory.internal.domain.DamageDisposition;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code inventory.stock_damage_disposition}: one manager's decision on some units of a damage report, a child of
 * the report. Append-only in the table, so insert-only here: a decision is corrected by another document.
 */
@Entity
@Table(name = "stock_damage_disposition", schema = "inventory")
public class StockDamageDispositionJpaEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "damage_report_id", nullable = false, updatable = false)
    private StockDamageReportJpaEntity report;

    @Column(name = "request_id", nullable = false, updatable = false)
    private UUID requestId;

    @Enumerated(EnumType.STRING)
    @Column(name = "disposition_type", nullable = false, length = 24, updatable = false)
    private DamageDisposition type;

    @Column(name = "quantity", nullable = false, updatable = false)
    private int quantity;

    @Column(name = "note", length = 1000, updatable = false)
    private String note;

    @Column(name = "record_number", length = 30, updatable = false)
    private String recordNumber;

    @Column(name = "stock_adjustment_id", updatable = false)
    private UUID stockAdjustmentId;

    @Column(name = "restore_location_code", length = 64, updatable = false)
    private String restoreLocationCode;

    @Column(name = "restored_stock_item_id", updatable = false)
    private UUID restoredStockItemId;

    @Column(name = "supplier_id", updatable = false)
    private UUID supplierId;

    @Column(name = "supplier_name", length = 200, updatable = false)
    private String supplierName;

    @Column(name = "decided_by", nullable = false, updatable = false)
    private UUID decidedBy;

    @Column(name = "decided_at", nullable = false, updatable = false)
    private Instant decidedAt;

    protected StockDamageDispositionJpaEntity() {
    }

    @SuppressWarnings("java:S107")
    public StockDamageDispositionJpaEntity(UUID id, UUID requestId, DamageDisposition type, int quantity, String note,
                                           String recordNumber, UUID stockAdjustmentId, String restoreLocationCode,
                                           UUID restoredStockItemId, UUID supplierId, String supplierName,
                                           UUID decidedBy, Instant decidedAt) {
        this.id = id;
        this.requestId = requestId;
        this.type = type;
        this.quantity = quantity;
        this.note = note;
        this.recordNumber = recordNumber;
        this.stockAdjustmentId = stockAdjustmentId;
        this.restoreLocationCode = restoreLocationCode;
        this.restoredStockItemId = restoredStockItemId;
        this.supplierId = supplierId;
        this.supplierName = supplierName;
        this.decidedBy = decidedBy;
        this.decidedAt = decidedAt;
    }

    void attachTo(StockDamageReportJpaEntity parent) {
        this.report = parent;
    }

    public UUID getId() { return id; }
    public UUID getRequestId() { return requestId; }
    public DamageDisposition getType() { return type; }
    public int getQuantity() { return quantity; }
    public String getNote() { return note; }
    public String getRecordNumber() { return recordNumber; }
    public UUID getStockAdjustmentId() { return stockAdjustmentId; }
    public String getRestoreLocationCode() { return restoreLocationCode; }
    public UUID getRestoredStockItemId() { return restoredStockItemId; }
    public UUID getSupplierId() { return supplierId; }
    public String getSupplierName() { return supplierName; }
    public UUID getDecidedBy() { return decidedBy; }
    public Instant getDecidedAt() { return decidedAt; }
}
