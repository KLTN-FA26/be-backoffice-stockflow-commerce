package com.stockflow.procurement.internal.entity;

import com.stockflow.procurement.internal.domain.PurchaseOrderStatus;
import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * JPA mapping of a purchase order (table {@code procurement.purchase_order}). Not the domain model.
 *
 * <p>STARTER ENTITY. {@code supplierId} is a same-schema reference. Lines live in
 * {@link POLineJpaEntity}; folding them into a PurchaseOrder aggregate is a TODO.</p>
 */
@Entity
@Table(name = "purchase_order", schema = "procurement",
        uniqueConstraints = @UniqueConstraint(name = "uk_purchase_order_number", columnNames = "po_number"))
public class PurchaseOrderJpaEntity extends BaseEntity {

    @Column(name = "po_number", nullable = false, length = 64)
    private String poNumber;

    @Column(name = "supplier_id", nullable = false)
    private UUID supplierId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private PurchaseOrderStatus status;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "total_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "expected_at")
    private LocalDate expectedAt;

    protected PurchaseOrderJpaEntity() {
    }

    public PurchaseOrderJpaEntity(UUID id, String poNumber, UUID supplierId, PurchaseOrderStatus status,
                                  String currency, BigDecimal totalAmount, LocalDate expectedAt) {
        super(id);
        this.poNumber = poNumber;
        this.supplierId = supplierId;
        this.status = status;
        this.currency = currency;
        this.totalAmount = totalAmount;
        this.expectedAt = expectedAt;
    }

    public String getPoNumber() { return poNumber; }
    public UUID getSupplierId() { return supplierId; }
    public PurchaseOrderStatus getStatus() { return status; }
    public String getCurrency() { return currency; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public LocalDate getExpectedAt() { return expectedAt; }

    // TODO: consider MoneyEmbeddable for (amount, currency); payment terms; three-way-match tolerance.
}
