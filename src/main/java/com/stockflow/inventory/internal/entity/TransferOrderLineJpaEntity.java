package com.stockflow.inventory.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.util.UUID;

/** {@code inventory.transfer_order_line}. Received and damaged quantities belong to receipt (SCRUM-328). */
@Entity
@Table(name = "transfer_order_line", schema = "inventory")
public class TransferOrderLineJpaEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transfer_order_id", nullable = false, updatable = false)
    private TransferOrderJpaEntity transferOrder;

    @Column(name = "line_no", nullable = false, updatable = false)
    private int lineNo;

    @Column(name = "sku", nullable = false, length = 64, updatable = false)
    private String sku;

    @Column(name = "lot_number", length = 64, updatable = false)
    private String lotNumber;

    @Column(name = "requested_qty", nullable = false, updatable = false)
    private int requestedQty;

    @Column(name = "shipped_qty", nullable = false)
    private int shippedQty;

    protected TransferOrderLineJpaEntity() {
    }

    public TransferOrderLineJpaEntity(UUID id, int lineNo, String sku, String lotNumber, int requestedQty) {
        super(id);
        this.lineNo = lineNo;
        this.sku = sku;
        this.lotNumber = lotNumber;
        this.requestedQty = requestedQty;
    }

    void attachTo(TransferOrderJpaEntity order) {
        this.transferOrder = order;
    }

    public void ship(int quantity) {
        this.shippedQty = quantity;
    }

    public int getLineNo() { return lineNo; }
    public String getSku() { return sku; }
    public String getLotNumber() { return lotNumber; }
    public int getRequestedQty() { return requestedQty; }
    public int getShippedQty() { return shippedQty; }
}
