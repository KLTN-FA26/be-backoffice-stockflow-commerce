package com.stockflow.order.internal.entity;

import com.stockflow.order.api.OrderStatus;
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
