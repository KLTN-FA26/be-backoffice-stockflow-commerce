package com.stockflow.inventory.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import com.stockflow.inventory.internal.domain.DamageReason;
import com.stockflow.inventory.internal.domain.StockDamageReport;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * {@code inventory.stock_damage_report}. Not the domain model: {@code StockDamageReport} is, and the mapper copies
 * between the two. Every reported fact is insert-only ({@code tg_stock_damage_report_immutable} says it again in
 * the table); what the report gains later is decisions, which are child rows.
 *
 * <p>The children are added, never replaced: both child tables are append-only, so the {@code clear()}-and-re-add
 * pattern of {@code StockItemJpaEntity} would turn every save into DELETEs the database refuses. Neither collection
 * removes orphans, for the same reason.</p>
 */
@Entity
@Table(name = "stock_damage_report", schema = "inventory")
public class StockDamageReportJpaEntity extends BaseEntity {

    @Column(name = "report_number", nullable = false, length = 30, updatable = false)
    private String reportNumber;

    @Column(name = "request_id", nullable = false, updatable = false)
    private UUID requestId;

    @Enumerated(EnumType.STRING)
    @Column(name = "origin", nullable = false, length = 16, updatable = false)
    private StockDamageReport.Origin origin;

    @Column(name = "warehouse_id", nullable = false, updatable = false)
    private UUID warehouseId;

    @Column(name = "sku", nullable = false, length = 64, updatable = false)
    private String sku;

    @Column(name = "lot_number", length = 64, updatable = false)
    private String lotNumber;

    @Column(name = "serial_number", length = 100, updatable = false)
    private String serialNumber;

    @Column(name = "received_at", updatable = false)
    private Instant receivedAt;

    @Column(name = "lot_id", updatable = false)
    private UUID lotId;

    @Column(name = "source_stock_item_id", nullable = false, updatable = false)
    private UUID sourceStockItemId;

    @Column(name = "source_location_code", nullable = false, length = 64, updatable = false)
    private String sourceLocationCode;

    @Column(name = "source_stock_package_id", updatable = false)
    private UUID sourceStockPackageId;

    @Column(name = "damaged_stock_item_id", nullable = false, updatable = false)
    private UUID damagedStockItemId;

    @Column(name = "damage_location_code", nullable = false, length = 64, updatable = false)
    private String damageLocationCode;

    @Column(name = "quantity", nullable = false, updatable = false)
    private int quantity;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason_code", nullable = false, length = 24, updatable = false)
    private DamageReason reasonCode;

    @Column(name = "note", length = 1000, updatable = false)
    private String note;

    @Column(name = "order_id", updatable = false)
    private UUID orderId;

    @Column(name = "pick_line_id", updatable = false)
    private UUID pickLineId;

    @Column(name = "reservation_id", updatable = false)
    private UUID reservationId;

    @Column(name = "impaired_reservation_id", updatable = false)
    private UUID impairedReservationId;

    @Column(name = "reported_by", nullable = false, updatable = false)
    private UUID reportedBy;

    @Column(name = "reported_at", nullable = false, updatable = false)
    private Instant reportedAt;

    /** Lazy, and read inside the transaction: a detail screen needs them, a list never does. */
    @OneToMany(mappedBy = "report", cascade = {CascadeType.PERSIST, CascadeType.MERGE}, fetch = FetchType.LAZY)
    @OrderBy("sortOrder ASC")
    private List<StockDamagePhotoJpaEntity> photos = new ArrayList<>();

    @OneToMany(mappedBy = "report", cascade = {CascadeType.PERSIST, CascadeType.MERGE}, fetch = FetchType.LAZY)
    @OrderBy("decidedAt ASC, id ASC")
    private List<StockDamageDispositionJpaEntity> dispositions = new ArrayList<>();

    protected StockDamageReportJpaEntity() {
    }

    @SuppressWarnings("java:S107")
    public StockDamageReportJpaEntity(UUID id, String reportNumber, UUID requestId, StockDamageReport.Origin origin,
                                      UUID warehouseId, String sku, String lotNumber, String serialNumber,
                                      Instant receivedAt, UUID lotId, UUID sourceStockItemId,
                                      String sourceLocationCode, UUID sourceStockPackageId, UUID damagedStockItemId,
                                      String damageLocationCode, int quantity, DamageReason reasonCode, String note,
                                      UUID reportedBy, Instant reportedAt) {
        super(id);
        this.reportNumber = reportNumber;
        this.requestId = requestId;
        this.origin = origin;
        this.warehouseId = warehouseId;
        this.sku = sku;
        this.lotNumber = lotNumber;
        this.serialNumber = serialNumber;
        this.receivedAt = receivedAt;
        this.lotId = lotId;
        this.sourceStockItemId = sourceStockItemId;
        this.sourceLocationCode = sourceLocationCode;
        this.sourceStockPackageId = sourceStockPackageId;
        this.damagedStockItemId = damagedStockItemId;
        this.damageLocationCode = damageLocationCode;
        this.quantity = quantity;
        this.reasonCode = reasonCode;
        this.note = note;
        this.reportedBy = reportedBy;
        this.reportedAt = reportedAt;
    }

    /** A picker's report: the order, the pick line, the allocation, and its impaired share if any. */
    public StockDamageReportJpaEntity pickedFor(UUID orderId, UUID pickLineId, UUID reservationId,
                                                UUID impairedReservationId) {
        this.orderId = orderId;
        this.pickLineId = pickLineId;
        this.reservationId = reservationId;
        this.impairedReservationId = impairedReservationId;
        return this;
    }

    public void addPhoto(StockDamagePhotoJpaEntity photo) {
        photo.attachTo(this);
        photos.add(photo);
    }

    public void addDisposition(StockDamageDispositionJpaEntity disposition) {
        disposition.attachTo(this);
        dispositions.add(disposition);
    }

    public String getReportNumber() { return reportNumber; }
    public UUID getRequestId() { return requestId; }
    public StockDamageReport.Origin getOrigin() { return origin; }
    public UUID getWarehouseId() { return warehouseId; }
    public String getSku() { return sku; }
    public String getLotNumber() { return lotNumber; }
    public String getSerialNumber() { return serialNumber; }
    public Instant getReceivedAt() { return receivedAt; }
    public UUID getLotId() { return lotId; }
    public UUID getSourceStockItemId() { return sourceStockItemId; }
    public String getSourceLocationCode() { return sourceLocationCode; }
    public UUID getSourceStockPackageId() { return sourceStockPackageId; }
    public UUID getDamagedStockItemId() { return damagedStockItemId; }
    public String getDamageLocationCode() { return damageLocationCode; }
    public int getQuantity() { return quantity; }
    public DamageReason getReasonCode() { return reasonCode; }
    public String getNote() { return note; }
    public UUID getOrderId() { return orderId; }
    public UUID getPickLineId() { return pickLineId; }
    public UUID getReservationId() { return reservationId; }
    public UUID getImpairedReservationId() { return impairedReservationId; }
    public UUID getReportedBy() { return reportedBy; }
    public Instant getReportedAt() { return reportedAt; }
    public List<StockDamagePhotoJpaEntity> getPhotos() { return Collections.unmodifiableList(photos); }
    public List<StockDamageDispositionJpaEntity> getDispositions() { return Collections.unmodifiableList(dispositions); }
}
