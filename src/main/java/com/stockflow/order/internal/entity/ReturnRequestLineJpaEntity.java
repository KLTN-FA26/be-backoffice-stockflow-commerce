package com.stockflow.order.internal.entity;

import com.stockflow.order.internal.domain.ReturnItemCondition;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * {@code tg_return_request_line_check}, which holds the line to what was delivered.
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

    @Column(name = "received_sku", length = 64)
    private String receivedSku;

    @Column(name = "received_quantity")
    private Integer receivedQuantity;

    @Column(name = "lot_number", length = 64)
    private String lotNumber;

    @Column(name = "accepted_quantity")
    private Integer acceptedQuantity;

    @Column(name = "rejected_quantity")
    private Integer rejectedQuantity;

    @Enumerated(EnumType.STRING)
    @Column(name = "item_condition", length = 16)
    private ReturnItemCondition itemCondition;

    @Column(name = "inspection_note", length = 1000)
    private String inspectionNote;

    @Column(name = "restock_quantity", nullable = false)
    private int restockQuantity;

    @Column(name = "scrap_quantity", nullable = false)
    private int scrapQuantity;

    @Column(name = "customer_return_quantity", nullable = false)
    private int customerReturnQuantity;

    @Column(name = "restock_location_code", length = 64)
    private String restockLocationCode;

    @Column(name = "scrap_adjustment_id")
    private UUID scrapAdjustmentId;

    @Column(name = "customer_return_adjustment_id")
    private UUID customerReturnAdjustmentId;

    @Column(name = "replacement_quantity", nullable = false)
    private int replacementQuantity;

    @Column(name = "credit_quantity", nullable = false)
    private int creditQuantity;

    @Column(name = "credit_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal creditAmount = BigDecimal.ZERO;

    @Column(name = "replacement_order_line_id")
    private UUID replacementOrderLineId;

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

    public void apply(Integer approved, String receivedSku, Integer received, String lot, Integer accepted,
                      Integer rejected, ReturnItemCondition condition, String inspectionNote, int restock, int scrap,
                      int customerReturn, String restockLocation, UUID scrapAdjustment, UUID customerReturnAdjustment,
                      int replacement, int credit, BigDecimal creditAmount, UUID replacementOrderLine) {
        this.approvedQuantity = approved;
        this.receivedSku = receivedSku;
        this.receivedQuantity = received;
        this.lotNumber = lot;
        this.acceptedQuantity = accepted;
        this.rejectedQuantity = rejected;
        this.itemCondition = condition;
        this.inspectionNote = inspectionNote;
        this.restockQuantity = restock;
        this.scrapQuantity = scrap;
        this.customerReturnQuantity = customerReturn;
        this.restockLocationCode = restockLocation;
        this.scrapAdjustmentId = scrapAdjustment;
        this.customerReturnAdjustmentId = customerReturnAdjustment;
        this.replacementQuantity = replacement;
        this.creditQuantity = credit;
        this.creditAmount = creditAmount;
        this.replacementOrderLineId = replacementOrderLine;
    }

    public UUID getId() { return id; }
    public UUID getOrderLineId() { return orderLineId; }
    public String getSku() { return sku; }
    public boolean isCustomPrinted() { return customPrinted; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public int getQuantity() { return quantity; }
    public Integer getApprovedQuantity() { return approvedQuantity; }
    public String getReceivedSku() { return receivedSku; }
    public Integer getReceivedQuantity() { return receivedQuantity; }
    public String getLotNumber() { return lotNumber; }
    public Integer getAcceptedQuantity() { return acceptedQuantity; }
    public Integer getRejectedQuantity() { return rejectedQuantity; }
    public ReturnItemCondition getItemCondition() { return itemCondition; }
    public String getInspectionNote() { return inspectionNote; }
    public int getRestockQuantity() { return restockQuantity; }
    public int getScrapQuantity() { return scrapQuantity; }
    public int getCustomerReturnQuantity() { return customerReturnQuantity; }
    public String getRestockLocationCode() { return restockLocationCode; }
    public UUID getScrapAdjustmentId() { return scrapAdjustmentId; }
    public UUID getCustomerReturnAdjustmentId() { return customerReturnAdjustmentId; }
    public int getReplacementQuantity() { return replacementQuantity; }
    public int getCreditQuantity() { return creditQuantity; }
    public BigDecimal getCreditAmount() { return creditAmount; }
    public UUID getReplacementOrderLineId() { return replacementOrderLineId; }
}
