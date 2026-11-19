package com.stockflow.order.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code ordering.order_address_change} (V20261204006110, WF04 G2): every change of an order's delivery address by Sales
 * before its first carton was labelled (kltn-docs 17 §4.7, BR-16) - who, when, at what status, from what to what.
 * Append-only, the order's history of its address.
 */
@Entity
@Table(name = "order_address_change", schema = "ordering")
public class OrderAddressChangeJpaEntity extends BaseEntity {

    @Column(name = "order_id", nullable = false, updatable = false) private UUID orderId;
    @Column(name = "order_status", nullable = false, updatable = false, length = 32) private String orderStatus;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "recipientName", column = @Column(name = "previous_recipient_name", length = 200, updatable = false)),
            @AttributeOverride(name = "phone", column = @Column(name = "previous_phone", length = 32, updatable = false)),
            @AttributeOverride(name = "line1", column = @Column(name = "previous_line1", length = 255, updatable = false)),
            @AttributeOverride(name = "line2", column = @Column(name = "previous_line2", length = 255, updatable = false)),
            @AttributeOverride(name = "wardCode", column = @Column(name = "previous_ward_code", length = 20, updatable = false)),
            @AttributeOverride(name = "wardName", column = @Column(name = "previous_ward_name", length = 120, updatable = false)),
            @AttributeOverride(name = "provinceCode", column = @Column(name = "previous_province_code", length = 20, updatable = false)),
            @AttributeOverride(name = "provinceName", column = @Column(name = "previous_province_name", length = 120, updatable = false)),
            @AttributeOverride(name = "countryCode", column = @Column(name = "previous_country_code", length = 2, updatable = false)),
            @AttributeOverride(name = "postalCode", column = @Column(name = "previous_postal_code", length = 20, updatable = false))
    })
    private OrderAddressJpaEmbeddable previousAddress;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "recipientName", column = @Column(name = "new_recipient_name", length = 200, updatable = false)),
            @AttributeOverride(name = "phone", column = @Column(name = "new_phone", length = 32, updatable = false)),
            @AttributeOverride(name = "line1", column = @Column(name = "new_line1", length = 255, updatable = false)),
            @AttributeOverride(name = "line2", column = @Column(name = "new_line2", length = 255, updatable = false)),
            @AttributeOverride(name = "wardCode", column = @Column(name = "new_ward_code", length = 20, updatable = false)),
            @AttributeOverride(name = "wardName", column = @Column(name = "new_ward_name", length = 120, updatable = false)),
            @AttributeOverride(name = "provinceCode", column = @Column(name = "new_province_code", length = 20, updatable = false)),
            @AttributeOverride(name = "provinceName", column = @Column(name = "new_province_name", length = 120, updatable = false)),
            @AttributeOverride(name = "countryCode", column = @Column(name = "new_country_code", length = 2, updatable = false)),
            @AttributeOverride(name = "postalCode", column = @Column(name = "new_postal_code", length = 20, updatable = false))
    })
    private OrderAddressJpaEmbeddable newAddress;

    @Column(name = "changed_by", nullable = false, updatable = false) private UUID changedBy;
    @Column(name = "changed_at", nullable = false, updatable = false) private Instant changedAt;

    protected OrderAddressChangeJpaEntity() {
    }

    public OrderAddressChangeJpaEntity(UUID id, UUID orderId, String orderStatus,
                                       OrderAddressJpaEmbeddable previousAddress, OrderAddressJpaEmbeddable newAddress,
                                       UUID changedBy, Instant changedAt) {
        super(id);
        this.orderId = orderId;
        this.orderStatus = orderStatus;
        this.previousAddress = previousAddress;
        this.newAddress = newAddress;
        this.changedBy = changedBy;
        this.changedAt = changedAt;
    }

    public UUID getOrderId() { return orderId; }
    public String getOrderStatus() { return orderStatus; }
    public OrderAddressJpaEmbeddable getPreviousAddress() { return previousAddress; }
    public OrderAddressJpaEmbeddable getNewAddress() { return newAddress; }
    public UUID getChangedBy() { return changedBy; }
    public Instant getChangedAt() { return changedAt; }
}
