package com.stockflow.order.internal.entity;

import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.PaymentTerm;
import com.stockflow.common.persistence.BaseEntity;
import com.stockflow.common.security.ScopedEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Embedded;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * JPA mapping of an order.
 *
 * <p>Schema {@code ordering}, not {@code order}: {@code ORDER} is a reserved word in SQL, and a
 * schema by that name forces every query to quote it. The module is still called {@code order} —
 * only the schema name works around the reserved word, and {@code docs/adr/0005} records why so
 * nobody "fixes" the inconsistency later.</p>
 *
 * <p>The unique constraint on {@code request_id} is what actually enforces checkout idempotency.
 * {@code OrderServiceImpl} checks first, but two simultaneous submissions can both pass that
 * check; only the database can refuse the second insert.</p>
 */
@Entity
@Table(
        name = "customer_order",
        schema = "ordering",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_order_number", columnNames = "order_number"),
                @UniqueConstraint(name = "uk_order_request", columnNames = "request_id")
        },
        indexes = {
                @Index(name = "ix_order_customer", columnList = "customer_id"),
                @Index(name = "ix_order_status_placed", columnList = "status, placed_at")
        })
public class OrderJpaEntity extends BaseEntity implements ScopedEntity {

    /**
     * Read only for its attribute names by {@link ScopedEntity}; never persisted, never returned.
     * A static instance rather than a fresh one per call because the names are constant and this
     * is on the path of every scoped query.
     */
    public static final OrderJpaEntity SCOPE_PROTOTYPE = new OrderJpaEntity();

    @Column(name = "order_number", nullable = false, length = 32, updatable = false)
    private String orderNumber;

    /** Reference into the {@code customer} schema; no cross-schema foreign key, by policy. */
    @Column(name = "customer_id")
    private UUID customerId;

    @Column(name = "contact_name", length = 200)
    private String contactName;

    @Column(name = "contact_email", length = 320)
    private String contactEmail;

    @Column(name = "contact_phone", length = 32)
    private String contactPhone;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "recipientName", column = @Column(name = "shipping_recipient_name", length = 200)),
            @AttributeOverride(name = "phone", column = @Column(name = "shipping_phone", length = 32)),
            @AttributeOverride(name = "line1", column = @Column(name = "shipping_line1", length = 255)),
            @AttributeOverride(name = "line2", column = @Column(name = "shipping_line2", length = 255)),
            @AttributeOverride(name = "wardCode", column = @Column(name = "shipping_ward_code", length = 20)),
            @AttributeOverride(name = "wardName", column = @Column(name = "shipping_ward_name", length = 120)),
            @AttributeOverride(name = "provinceCode", column = @Column(name = "shipping_province_code", length = 20)),
            @AttributeOverride(name = "provinceName", column = @Column(name = "shipping_province_name", length = 120)),
            @AttributeOverride(name = "countryCode", column = @Column(name = "shipping_country_code", length = 2)),
            @AttributeOverride(name = "postalCode", column = @Column(name = "shipping_postal_code", length = 20))
    })
    private OrderAddressJpaEmbeddable shippingAddress;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "recipientName", column = @Column(name = "billing_recipient_name", length = 200)),
            @AttributeOverride(name = "phone", column = @Column(name = "billing_phone", length = 32)),
            @AttributeOverride(name = "line1", column = @Column(name = "billing_line1", length = 255)),
            @AttributeOverride(name = "line2", column = @Column(name = "billing_line2", length = 255)),
            @AttributeOverride(name = "wardCode", column = @Column(name = "billing_ward_code", length = 20)),
            @AttributeOverride(name = "wardName", column = @Column(name = "billing_ward_name", length = 120)),
            @AttributeOverride(name = "provinceCode", column = @Column(name = "billing_province_code", length = 20)),
            @AttributeOverride(name = "provinceName", column = @Column(name = "billing_province_name", length = 120)),
            @AttributeOverride(name = "countryCode", column = @Column(name = "billing_country_code", length = 2)),
            @AttributeOverride(name = "postalCode", column = @Column(name = "billing_postal_code", length = 20))
    })
    private OrderAddressJpaEmbeddable billingAddress;

    @Column(name = "request_id", nullable = false, updatable = false)
    private UUID requestId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private OrderStatus status;

    /**
     * Denormalised order total, recomputed from the lines on every save.
     *
     * <p>The aggregate derives it, so this column is strictly redundant — and it is kept because
     * the orders list screen and every revenue report would otherwise have to join and sum the
     * lines to display one number per row.</p>
     */
    @Column(name = "total_amount", nullable = false, precision = 19, scale = 4)
    private java.math.BigDecimal totalAmount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "placed_at", nullable = false, updatable = false)
    private Instant placedAt;

    @Column(name = "cancellation_reason", length = 500)
    private String cancellationReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_term", nullable = false, length = 16)
    private PaymentTerm paymentTerm = PaymentTerm.PREPAID;

    @Column(name = "deposit_required", precision = 19, scale = 4)
    private java.math.BigDecimal depositRequired;

    @Column(name = "deposit_received_at")
    private Instant depositReceivedAt;

    /** V20261021000200 (G2-3): how a deposit order's balance is collected; a whole-order handover's COD. */
    @Enumerated(EnumType.STRING)
    @Column(name = "balance_policy", length = 16)
    private com.stockflow.order.api.BalancePolicy balancePolicy;

    @Column(name = "cod_amount", nullable = false, precision = 19, scale = 4)
    private java.math.BigDecimal codAmount = java.math.BigDecimal.ZERO;

    /** V20261022000400 (PACK-PARTIAL / PACK-EARLY): Sales allowed shipping in parts; shipped early, printing goes on. */
    @Column(name = "allow_partial_shipment", nullable = false)
    private boolean allowPartialShipment;

    @Column(name = "awaiting_production", nullable = false)
    private boolean awaitingProduction;

    @Column(name = "warehouse_id")
    private UUID warehouseId;

    @Column(name = "released_at")
    private Instant releasedAt;

    @Column(name = "released_by")
    private UUID releasedBy;

    /** Money received so far (SCRUM-460); the payment status is derived from it. */
    @Column(name = "paid_amount", nullable = false, precision = 19, scale = 4)
    private java.math.BigDecimal paidAmount = java.math.BigDecimal.ZERO;

    @Column(name = "paid_in_full_at")
    private Instant paidInFullAt;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(name = "cancellation_reason_code", length = 32)
    private com.stockflow.order.api.CancellationReasonCode cancellationReasonCode;

    @Column(name = "cancellation_retained_amount", precision = 19, scale = 4)
    private java.math.BigDecimal cancellationRetainedAmount;

    /** V20261017001000: production started printing for the order (the cancellation rules read it). */
    @Column(name = "printing_started_at")
    private Instant printingStartedAt;

    /** V20261017001000: a package of the order was completed (03 §9: no cancellation from here). */
    @Column(name = "packed_at")
    private Instant packedAt;

    /** V20261017000430, decision Q2: the terms of an agreed amount kept on cancellation. */
    @Column(name = "cancellation_fee_amount", precision = 19, scale = 4)
    private java.math.BigDecimal cancellationFeeAmount;

    @Column(name = "cancellation_fee_reason", length = 500)
    private String cancellationFeeReason;

    @Column(name = "cancellation_fee_detail", length = 2000)
    private String cancellationFeeDetail;

    /** V20261021000100 (G3-2): why it is on hold and where it goes back; the milestones; the money billed. */
    @Enumerated(EnumType.STRING)
    @Column(name = "hold_reason", length = 24)
    private com.stockflow.order.api.HoldReason holdReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "status_before_hold", length = 32)
    private OrderStatus statusBeforeHold;

    @Column(name = "hold_note", length = 1000)
    private String holdNote;

    @Column(name = "shipped_at")
    private Instant shippedAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_failure_reason", length = 24)
    private com.stockflow.order.api.DeliveryFailureReason deliveryFailureReason;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "refunded_at")
    private Instant refundedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "billed_amount", nullable = false, precision = 19, scale = 4)
    private java.math.BigDecimal billedAmount = java.math.BigDecimal.ZERO;

    @Column(name = "receivable_paid_amount", nullable = false, precision = 19, scale = 4)
    private java.math.BigDecimal receivablePaidAmount = java.math.BigDecimal.ZERO;

    @Column(name = "remainder_refund_amount", nullable = false, precision = 19, scale = 4)
    private java.math.BigDecimal remainderRefundAmount = java.math.BigDecimal.ZERO;

    /** A credit order's days to pay after delivery, as sold (SCRUM-427). */
    @Column(name = "credit_term_days")
    private Integer creditTermDays;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL,
            orphanRemoval = true, fetch = FetchType.LAZY)
    @org.hibernate.annotations.BatchSize(size = 50)
    private List<OrderLineJpaEntity> lines = new ArrayList<>();

    protected OrderJpaEntity() {
    }

    public OrderJpaEntity(UUID id, String orderNumber, UUID customerId, UUID requestId, OrderStatus status,
                   java.math.BigDecimal totalAmount, String currency, Instant placedAt,
                   String cancellationReason) {
        this(id, orderNumber, customerId, requestId, status, totalAmount, currency, placedAt,
                cancellationReason, null, null, null, null, null);
    }

    public OrderJpaEntity(UUID id, String orderNumber, UUID customerId, UUID requestId, OrderStatus status,
                          java.math.BigDecimal totalAmount, String currency, Instant placedAt,
                          String cancellationReason, String contactName, String contactEmail,
                          String contactPhone, OrderAddressJpaEmbeddable shippingAddress,
                          OrderAddressJpaEmbeddable billingAddress) {
        super(id);
        this.orderNumber = orderNumber;
        this.customerId = customerId;
        this.requestId = requestId;
        this.status = status;
        this.totalAmount = totalAmount;
        this.currency = currency;
        this.placedAt = placedAt;
        this.cancellationReason = cancellationReason;
        this.contactName = contactName;
        this.contactEmail = contactEmail;
        this.contactPhone = contactPhone;
        this.shippingAddress = shippingAddress;
        this.billingAddress = billingAddress;
    }

    public void apply(OrderStatus status, java.math.BigDecimal totalAmount,
               String currency, String cancellationReason) {
        this.status = status;
        this.totalAmount = totalAmount;
        this.currency = currency;
        this.cancellationReason = cancellationReason;
    }

    public void applyTermsAndRelease(PaymentTerm paymentTerm, java.math.BigDecimal depositRequired,
                                     Instant depositReceivedAt, UUID warehouseId, Instant releasedAt,
                                     UUID releasedBy) {
        this.paymentTerm = paymentTerm == null ? PaymentTerm.PREPAID : paymentTerm;
        this.depositRequired = depositRequired;
        this.depositReceivedAt = depositReceivedAt;
        this.warehouseId = warehouseId;
        this.releasedAt = releasedAt;
        this.releasedBy = releasedBy;
    }

    public void applyBalance(com.stockflow.order.api.BalancePolicy balancePolicy, java.math.BigDecimal codAmount) {
        this.balancePolicy = balancePolicy;
        this.codAmount = codAmount == null ? java.math.BigDecimal.ZERO : codAmount;
    }

    public com.stockflow.order.api.BalancePolicy getBalancePolicy() { return balancePolicy; }

    public void applyPartialShipment(boolean allowed, boolean awaiting) {
        this.allowPartialShipment = allowed;
        this.awaitingProduction = awaiting;
    }

    public boolean isAllowPartialShipment() { return allowPartialShipment; }
    public boolean isAwaitingProduction() { return awaitingProduction; }
    public java.math.BigDecimal getCodAmount() { return codAmount; }

    public void applyPaymentAndCancellation(java.math.BigDecimal paidAmount, Instant paidInFullAt,
                                            com.stockflow.order.api.CancellationReasonCode code,
                                            java.math.BigDecimal retained, Integer creditTermDays) {
        this.creditTermDays = creditTermDays;
        this.paidAmount = paidAmount == null ? java.math.BigDecimal.ZERO : paidAmount;
        this.paidInFullAt = paidInFullAt;
        this.cancellationReasonCode = code;
        this.cancellationRetainedAmount = retained;
    }

    public void replaceLines(List<OrderLineJpaEntity> replacement) {
        this.lines.clear();
        replacement.forEach(child -> {
            child.attachTo(this);
            this.lines.add(child);
        });
    }

    public String getOrderNumber() { return orderNumber; }
    public UUID getCustomerId() { return customerId; }
    public String getContactName() { return contactName; }
    public String getContactEmail() { return contactEmail; }
    public String getContactPhone() { return contactPhone; }
    public OrderAddressJpaEmbeddable getShippingAddress() { return shippingAddress; }
    public OrderAddressJpaEmbeddable getBillingAddress() { return billingAddress; }
    public UUID getRequestId() { return requestId; }
    public OrderStatus getStatus() { return status; }
    public java.math.BigDecimal getTotalAmount() { return totalAmount; }
    public String getCurrency() { return currency; }
    public Instant getPlacedAt() { return placedAt; }
    public String getCancellationReason() { return cancellationReason; }
    public PaymentTerm getPaymentTerm() { return paymentTerm; }
    public java.math.BigDecimal getDepositRequired() { return depositRequired; }
    public Instant getDepositReceivedAt() { return depositReceivedAt; }
    public UUID getWarehouseId() { return warehouseId; }
    public Instant getReleasedAt() { return releasedAt; }
    public UUID getReleasedBy() { return releasedBy; }
    public java.math.BigDecimal getPaidAmount() { return paidAmount; }
    public Instant getPaidInFullAt() { return paidInFullAt; }
    public com.stockflow.order.api.CancellationReasonCode getCancellationReasonCode() { return cancellationReasonCode; }
    public java.math.BigDecimal getCancellationRetainedAmount() { return cancellationRetainedAmount; }
    public String getCancellationFeeReason() { return cancellationFeeReason; }
    public java.math.BigDecimal getCancellationFeeAmount() { return cancellationFeeAmount; }
    public String getCancellationFeeDetail() { return cancellationFeeDetail; }

    public void applyFulfilmentFacts(Instant printingStarted, Instant packed) {
        this.printingStartedAt = printingStarted;
        this.packedAt = packed;
    }

    public Instant getPrintingStartedAt() { return printingStartedAt; }
    public Instant getPackedAt() { return packedAt; }

    public void applyCancellationFee(java.math.BigDecimal amount, String reason, String detail) {
        this.cancellationFeeAmount = amount;
        this.cancellationFeeReason = reason;
        this.cancellationFeeDetail = detail;
    }
    public void applyLifecycle(com.stockflow.order.api.HoldReason holdReason, OrderStatus statusBeforeHold,
                               String holdNote, Instant shippedAt, Instant deliveredAt,
                               com.stockflow.order.api.DeliveryFailureReason deliveryFailureReason,
                               Instant completedAt, Instant refundedAt, Instant closedAt,
                               java.math.BigDecimal billedAmount, java.math.BigDecimal receivablePaidAmount,
                               java.math.BigDecimal remainderRefundAmount) {
        this.holdReason = holdReason;
        this.statusBeforeHold = statusBeforeHold;
        this.holdNote = holdNote;
        this.shippedAt = shippedAt;
        this.deliveredAt = deliveredAt;
        this.deliveryFailureReason = deliveryFailureReason;
        this.completedAt = completedAt;
        this.refundedAt = refundedAt;
        this.closedAt = closedAt;
        this.billedAmount = billedAmount;
        this.receivablePaidAmount = receivablePaidAmount;
        this.remainderRefundAmount = remainderRefundAmount;
    }

    /** One step of a status change: the row passes through each edge, so the transition trigger sees every one. */
    public void moveTo(OrderStatus next) {
        this.status = next;
    }

    public com.stockflow.order.api.HoldReason getHoldReason() { return holdReason; }
    public OrderStatus getStatusBeforeHold() { return statusBeforeHold; }
    public String getHoldNote() { return holdNote; }
    public Instant getShippedAt() { return shippedAt; }
    public Instant getDeliveredAt() { return deliveredAt; }
    public com.stockflow.order.api.DeliveryFailureReason getDeliveryFailureReason() { return deliveryFailureReason; }
    public Instant getCompletedAt() { return completedAt; }
    public Instant getRefundedAt() { return refundedAt; }
    public Instant getClosedAt() { return closedAt; }
    public java.math.BigDecimal getBilledAmount() { return billedAmount; }
    public java.math.BigDecimal getReceivablePaidAmount() { return receivablePaidAmount; }
    public java.math.BigDecimal getRemainderRefundAmount() { return remainderRefundAmount; }
    public Integer getCreditTermDays() { return creditTermDays; }
    public List<OrderLineJpaEntity> getLines() { return lines; }

    /**
     * An order belongs to the customer who placed it, so {@code DataScope.OWN} means
     * "customer_id = me".
     */
    @Override
    public String ownerAttribute() {
        return "customerId";
    }

    /**
     * Orders are not warehouse-specific - a single order can ship from several. Returning null
     * makes {@code DataScopeSpecifications} refuse WAREHOUSE scope on this type loudly, rather
     * than silently behaving like ALL.
     */
    @Override
    public String warehouseAttribute() {
        return null;
    }
}
