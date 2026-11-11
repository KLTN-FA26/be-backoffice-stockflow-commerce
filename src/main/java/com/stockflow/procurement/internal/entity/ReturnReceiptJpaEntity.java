package com.stockflow.procurement.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import com.stockflow.procurement.internal.domain.GoodsReceiptStatus;
import com.stockflow.procurement.internal.domain.GoodsReceiptType;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import org.hibernate.annotations.SQLRestriction;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The RETURN rows of {@code procurement.goods_receipts} (team plan 4D-2, V20261112000300). Not the domain model; see
 * {@code ReturnReceipt}. The other receipts of the table are {@link GoodsReceiptJpaEntity}'s: a return receipt shares
 * the table and the numbering with them, and nothing else - its columns are the order, the source and the quarantine
 * area, its children are cartons rather than lines.
 */
@Entity
@Table(name = "goods_receipts", schema = "procurement")
@SQLRestriction("receipt_type = 'RETURN'")
public class ReturnReceiptJpaEntity extends BaseEntity {

    @Column(name = "receipt_number", nullable = false, length = 30, updatable = false)
    private String receiptNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "receipt_type", nullable = false, length = 16, updatable = false)
    private GoodsReceiptType receiptType;

    @Column(name = "warehouse_id", nullable = false, updatable = false)
    private UUID warehouseId;

    @Column(name = "customer_order_id", nullable = false, updatable = false)
    private UUID customerOrderId;

    @Column(name = "return_shipment_id", updatable = false)
    private UUID returnShipmentId;

    @Column(name = "return_request_id", updatable = false)
    private UUID returnRequestId;

    @Column(name = "quarantine_location_id", nullable = false, updatable = false)
    private UUID quarantineLocationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private GoodsReceiptStatus status;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @Column(name = "received_by", nullable = false, updatable = false)
    private UUID receivedBy;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "confirmed_by")
    private UUID confirmedBy;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "note", updatable = false)
    private String note;

    @OneToMany(mappedBy = "receipt", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<ReturnReceiptCartonJpaEntity> cartons = new ArrayList<>();

    protected ReturnReceiptJpaEntity() {
    }

    public ReturnReceiptJpaEntity(UUID id, String receiptNumber, UUID warehouseId, UUID customerOrderId,
                                  UUID returnShipmentId, UUID returnRequestId, UUID quarantineLocationId,
                                  Instant receivedAt, UUID receivedBy, String note) {
        super(id);
        this.receiptNumber = receiptNumber;
        this.receiptType = GoodsReceiptType.RETURN;
        this.warehouseId = warehouseId;
        this.customerOrderId = customerOrderId;
        this.returnShipmentId = returnShipmentId;
        this.returnRequestId = returnRequestId;
        this.quarantineLocationId = quarantineLocationId;
        this.receivedAt = receivedAt;
        this.receivedBy = receivedBy;
        this.note = note;
        this.status = GoodsReceiptStatus.DRAFT;
    }

    public void apply(GoodsReceiptStatus status, Instant confirmedAt, UUID confirmedBy, Instant closedAt) {
        this.status = status;
        this.confirmedAt = confirmedAt;
        this.confirmedBy = confirmedBy;
        this.closedAt = closedAt;
    }

    public void addCarton(ReturnReceiptCartonJpaEntity carton) {
        carton.attachTo(this);
        cartons.add(carton);
    }

    public String getReceiptNumber() { return receiptNumber; }
    public GoodsReceiptType getReceiptType() { return receiptType; }
    public UUID getWarehouseId() { return warehouseId; }
    public UUID getCustomerOrderId() { return customerOrderId; }
    public UUID getReturnShipmentId() { return returnShipmentId; }
    public UUID getReturnRequestId() { return returnRequestId; }
    public UUID getQuarantineLocationId() { return quarantineLocationId; }
    public GoodsReceiptStatus getStatus() { return status; }
    public Instant getReceivedAt() { return receivedAt; }
    public UUID getReceivedBy() { return receivedBy; }
    public Instant getConfirmedAt() { return confirmedAt; }
    public UUID getConfirmedBy() { return confirmedBy; }
    public Instant getClosedAt() { return closedAt; }
    public String getNote() { return note; }
    public List<ReturnReceiptCartonJpaEntity> getCartons() { return cartons; }
}
