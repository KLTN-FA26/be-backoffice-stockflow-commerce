package com.stockflow.order.internal.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * {@code ordering.return_request_line}: a child of {@link ReturnRequestJpaEntity}, written only through it - no
 * version of its own (the request's guards the whole return), as {@code OrderLineJpaEntity}. Every write passes
 * {@code tg_return_request_line_check}, which holds the line to what was delivered. Since team plan 4D-3 a line counts
 * what was approved, what came back on the return receipt and what was refunded (V20261114000100).
 */
@Entity
@Table(name = "return_request_line", schema = "ordering")
public class ReturnRequestLineJpaEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "return_request_id", nullable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_return_request_line_request"))
    private ReturnRequestJpaEntity request;

    @Column(name = "order_line_id", nullable = false, updatable = false)
    private UUID orderLineId;

    @Column(name = "sku", nullable = false, length = 64, updatable = false)
    private String sku;

    @Column(name = "custom_printed", nullable = false, updatable = false)
    private boolean customPrinted;

    @Column(name = "unit_price", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal unitPrice;

    @Column(name = "quantity", nullable = false, updatable = false)
    private int quantity;

    @Column(name = "approved_quantity")
    private Integer approvedQuantity;

    @Column(name = "received_quantity")
    private Integer receivedQuantity;

    @Column(name = "refund_quantity", nullable = false)
    private int refundQuantity;

    @Column(name = "refund_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal refundAmount = BigDecimal.ZERO;

    protected ReturnRequestLineJpaEntity() {
    }

    public ReturnRequestLineJpaEntity(UUID id, UUID orderLineId, String sku, boolean customPrinted,
                                      BigDecimal unitPrice, int quantity) {
        this.id = id;
        this.orderLineId = orderLineId;
        this.sku = sku;
        this.customPrinted = customPrinted;
        this.unitPrice = unitPrice;
        this.quantity = quantity;
    }

    void attachTo(ReturnRequestJpaEntity owner) {
        this.request = owner;
    }

    public void apply(Integer approved, Integer received, int refund, BigDecimal refundAmount) {
        this.approvedQuantity = approved;
        this.receivedQuantity = received;
        this.refundQuantity = refund;
        this.refundAmount = refundAmount;
    }

    public UUID getId() { return id; }
    public UUID getOrderLineId() { return orderLineId; }
    public String getSku() { return sku; }
    public boolean isCustomPrinted() { return customPrinted; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public int getQuantity() { return quantity; }
    public Integer getApprovedQuantity() { return approvedQuantity; }
    public Integer getReceivedQuantity() { return receivedQuantity; }
    public int getRefundQuantity() { return refundQuantity; }
    public BigDecimal getRefundAmount() { return refundAmount; }
}
