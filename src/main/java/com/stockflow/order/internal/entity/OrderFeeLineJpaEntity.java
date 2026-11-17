package com.stockflow.order.internal.entity;

import com.stockflow.order.api.FeeKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * {@code ordering.order_fee_line}: a fee line of an order, written once when the order is placed from its quote
 * (4B-3). Not a {@code BaseEntity}, like the order's lines: it changes only with its order, whose version covers it.
 */
@Entity
@Table(name = "order_fee_line", schema = "ordering")
public class OrderFeeLineJpaEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false, updatable = false)
    private OrderJpaEntity order;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    private FeeKind kind;

    @Column(name = "sku", length = 64)
    private String sku;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "tax_rate", nullable = false, precision = 5, scale = 2)
    private BigDecimal taxRate;

    @Column(name = "tax_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal taxAmount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    protected OrderFeeLineJpaEntity() {
    }

    public OrderFeeLineJpaEntity(UUID id, FeeKind kind, String sku, BigDecimal amount, BigDecimal taxRate,
                                 BigDecimal taxAmount, String currency) {
        this.id = id;
        this.kind = kind;
        this.sku = sku;
        this.amount = amount;
        this.taxRate = taxRate;
        this.taxAmount = taxAmount;
        this.currency = currency;
    }

    void attachTo(OrderJpaEntity parent) {
        this.order = parent;
    }

    public UUID getId() { return id; }
    public FeeKind getKind() { return kind; }
    public String getSku() { return sku; }
    public BigDecimal getAmount() { return amount; }
    public BigDecimal getTaxRate() { return taxRate; }
    public BigDecimal getTaxAmount() { return taxAmount; }
    public String getCurrency() { return currency; }
}
