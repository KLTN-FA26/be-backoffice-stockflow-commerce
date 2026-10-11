package com.stockflow.procurement.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import org.hibernate.annotations.BatchSize;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * {@code procurement.goods_receipt_lines}. {@code received_qty} is NUMERIC(18,3) in the schema; the
 * stock it becomes is counted in whole units, so the domain carries an int and this maps the column
 * as it is.
 */
@Entity
@Table(name = "goods_receipt_lines", schema = "procurement")
public class GoodsReceiptLineJpaEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "receipt_id", nullable = false, updatable = false)
    private GoodsReceiptJpaEntity receipt;

    @Column(name = "po_line_id", nullable = false, updatable = false)
    private UUID poLineId;

    @Column(name = "inventory_item_id", nullable = false, updatable = false)
    private UUID inventoryItemId;

    @Column(name = "location_id", nullable = false, updatable = false)
    private UUID locationId;

    @Column(name = "received_qty", nullable = false, precision = 18, scale = 3, updatable = false)
    private BigDecimal receivedQty;

    @Column(name = "lot_number", length = 64, updatable = false)
    private String lotNumber;

    @Column(name = "expiry_date", updatable = false)
    private LocalDate expiryDate;

    @Column(name = "note", length = 255, updatable = false)
    private String note;

    @Column(name = "qc_required", nullable = false, updatable = false)
    private boolean qcRequired;

    @Column(name = "qc_location_id")
    private UUID qcLocationId;

    @Column(name = "moved_to_qc_at")
    private Instant movedToQcAt;

    @Column(name = "moved_to_qc_by")
    private UUID movedToQcBy;

    @OneToMany(mappedBy = "line", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @BatchSize(size = 50)
    private List<QcInspectionJpaEntity> inspections = new ArrayList<>();

    protected GoodsReceiptLineJpaEntity() {
    }

    public GoodsReceiptLineJpaEntity(UUID id, UUID poLineId, UUID inventoryItemId, UUID locationId,
                                     BigDecimal receivedQty, String lotNumber, LocalDate expiryDate, String note,
                                     boolean qcRequired) {
        super(id);
        this.poLineId = poLineId;
        this.inventoryItemId = inventoryItemId;
        this.locationId = locationId;
        this.receivedQty = receivedQty;
        this.lotNumber = lotNumber;
        this.expiryDate = expiryDate;
        this.note = note;
        this.qcRequired = qcRequired;
    }

    void attachTo(GoodsReceiptJpaEntity owner) {
        this.receipt = owner;
    }

    public void movedToQc(UUID qcLocationId, Instant at, UUID by) {
        this.qcLocationId = qcLocationId;
        this.movedToQcAt = at;
        this.movedToQcBy = by;
    }

    public void addInspection(QcInspectionJpaEntity inspection) {
        inspection.attachTo(this);
        inspections.add(inspection);
    }

    public UUID getPoLineId() { return poLineId; }
    public UUID getInventoryItemId() { return inventoryItemId; }
    public UUID getLocationId() { return locationId; }
    public BigDecimal getReceivedQty() { return receivedQty; }
    public String getLotNumber() { return lotNumber; }
    public LocalDate getExpiryDate() { return expiryDate; }
    public String getNote() { return note; }
    public boolean isQcRequired() { return qcRequired; }
    public UUID getQcLocationId() { return qcLocationId; }
    public Instant getMovedToQcAt() { return movedToQcAt; }
    public UUID getMovedToQcBy() { return movedToQcBy; }
    public List<QcInspectionJpaEntity> getInspections() { return inspections; }
}
