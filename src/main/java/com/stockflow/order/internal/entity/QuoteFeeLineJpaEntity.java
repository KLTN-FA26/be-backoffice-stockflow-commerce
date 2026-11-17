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
 * {@code ordering.quote_fee_line} (4B-3): a fee line of a quote. Like a quote line it has no version or audit columns
 * of its own: it changes only with its quote, persisted by cascade.
 */
@Entity
@Table(name = "quote_fee_line", schema = "ordering")
public class QuoteFeeLineJpaEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "quote_id", nullable = false, updatable = false)
    private QuoteJpaEntity quote;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    private FeeKind kind;

    @Column(name = "sku", length = 64)
    private String sku;

    @Column(name = "amount", precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "tax_class", length = 16)
    private String taxClass;

    @Column(name = "tax_rate", precision = 5, scale = 2)
    private BigDecimal taxRate;

    protected QuoteFeeLineJpaEntity() {
    }

    public QuoteFeeLineJpaEntity(UUID id) {
        this.id = id;
    }

    void attachTo(QuoteJpaEntity owner) {
        this.quote = owner;
    }

    public void apply(FeeKind kind, String sku, BigDecimal amount, String taxClass, BigDecimal taxRate) {
        this.kind = kind;
        this.sku = sku;
        this.amount = amount;
        this.taxClass = taxClass;
        this.taxRate = taxRate;
    }

    public UUID getId() { return id; }
    public FeeKind getKind() { return kind; }
    public String getSku() { return sku; }
    public BigDecimal getAmount() { return amount; }
    public String getTaxClass() { return taxClass; }
    public BigDecimal getTaxRate() { return taxRate; }
}
