package com.stockflow.order.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import com.stockflow.order.api.DeliveryFailureReason;
import com.stockflow.order.api.OrderShipmentStatus;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * {@code ordering.order_shipment}: one shipment of an order as the order knows it (G3-2, split shipments). The id is
 * fulfillment's shipment id. The units it carries ({@code ordering.order_shipment_line}) are written once, at the
 * handover; status and outcome change after.
 */
@Entity
@Table(name = "order_shipment", schema = "ordering")
public class OrderShipmentJpaEntity extends BaseEntity {

    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "order_shipment_line", schema = "ordering",
            joinColumns = @JoinColumn(name = "shipment_id"))
    @MapKeyColumn(name = "order_line_id")
    @Column(name = "quantity", nullable = false)
    private Map<UUID, Integer> quantities = new LinkedHashMap<>();

    @Column(name = "amount", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private OrderShipmentStatus status;

    @Column(name = "handed_over_at", nullable = false, updatable = false)
    private Instant handedOverAt;

    @Column(name = "handed_over_by", updatable = false)
    private UUID handedOverBy;

    @Column(name = "outcome_at")
    private Instant outcomeAt;

    @Column(name = "outcome_by")
    private UUID outcomeBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_reason", length = 24)
    private DeliveryFailureReason failureReason;

    @Column(name = "failure_note", length = 500)
    private String failureNote;

    @Column(name = "billed_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal billedAmount = BigDecimal.ZERO;

    /** G2-3: what the carrier collects on delivering it, fixed at the handover. */
    @Column(name = "cod_amount", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal codAmount = BigDecimal.ZERO;

    /** G4-2a: what the customer portal shows of the shipment, written once at the handover. */
    @Column(name = "shipment_code", updatable = false, length = 48)
    private String shipmentCode;

    @Column(name = "package_count", updatable = false)
    private Integer packageCount;

    @Column(name = "carrier_name", updatable = false, length = 120)
    private String carrierName;

    @Column(name = "delivery_mode", updatable = false, length = 16)
    private String deliveryMode;

    protected OrderShipmentJpaEntity() {
    }

    public OrderShipmentJpaEntity withCarriage(com.stockflow.order.api.ShipmentHandover.Carriage carriage) {
        if (carriage != null) {
            this.shipmentCode = carriage.shipmentCode();
            this.packageCount = carriage.packageCount();
            this.carrierName = carriage.carrierName();
            this.deliveryMode = carriage.deliveryMode();
        }
        return this;
    }

    /** Null for a shipment recorded before G4-2a. */
    public com.stockflow.order.api.ShipmentHandover.Carriage getCarriage() {
        return shipmentCode == null && packageCount == null ? null
                : new com.stockflow.order.api.ShipmentHandover.Carriage(shipmentCode,
                        packageCount == null ? 0 : packageCount, carrierName, deliveryMode);
    }

    public OrderShipmentJpaEntity(UUID id, UUID orderId, Map<UUID, Integer> quantities, BigDecimal amount,
                                  Instant handedOverAt, UUID handedOverBy) {
        super(id);
        this.orderId = orderId;
        this.quantities = new LinkedHashMap<>(quantities);
        this.amount = amount;
        this.handedOverAt = handedOverAt;
        this.handedOverBy = handedOverBy;
    }

    public OrderShipmentJpaEntity withCod(BigDecimal cod) {
        this.codAmount = cod == null ? BigDecimal.ZERO : cod;
        return this;
    }

    public void apply(OrderShipmentStatus status, Instant outcomeAt, UUID outcomeBy, DeliveryFailureReason failureReason,
                      String failureNote, BigDecimal billedAmount) {
        this.status = status;
        this.outcomeAt = outcomeAt;
        this.outcomeBy = outcomeBy;
        this.failureReason = failureReason;
        this.failureNote = failureNote;
        this.billedAmount = billedAmount;
    }

    public UUID getOrderId() { return orderId; }
    public Map<UUID, Integer> getQuantities() { return quantities; }
    public BigDecimal getAmount() { return amount; }
    public OrderShipmentStatus getStatus() { return status; }
    public Instant getHandedOverAt() { return handedOverAt; }
    public UUID getHandedOverBy() { return handedOverBy; }
    public Instant getOutcomeAt() { return outcomeAt; }
    public UUID getOutcomeBy() { return outcomeBy; }
    public DeliveryFailureReason getFailureReason() { return failureReason; }
    public String getFailureNote() { return failureNote; }
    public BigDecimal getBilledAmount() { return billedAmount; }
    public BigDecimal getCodAmount() { return codAmount; }
}
