package com.stockflow.procurement.internal.entity;

import com.stockflow.procurement.internal.domain.PurchaseOrderStatus;
import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * JPA mapping of a purchase order (table {@code procurement.purchase_order}). Not the domain model.
 *
 * <p>{@code supplierId} is a same-schema reference. {@code lines} is cascade-managed with the
 * order — same shape as {@code OrderJpaEntity.lines} — since a PO line has no independent lifecycle
 * of its own.</p>
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

    @OneToMany(mappedBy = "purchaseOrder", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.LAZY)
    @org.hibernate.annotations.BatchSize(size = 50)
    private List<POLineJpaEntity> lines = new ArrayList<>();

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

    /** Copies every editable field onto a managed row, so Hibernate's dirty checking writes the UPDATE. */
    public void apply(PurchaseOrderStatus status, BigDecimal totalAmount) {
        this.status = status;
        this.totalAmount = totalAmount;
    }

    public void replaceLines(List<POLineJpaEntity> replacement) {
        this.lines.clear();
        replacement.forEach(child -> {
            child.attachTo(this);
            this.lines.add(child);
        });
    }

    public String getPoNumber() { return poNumber; }
    public UUID getSupplierId() { return supplierId; }
    public PurchaseOrderStatus getStatus() { return status; }
    public String getCurrency() { return currency; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public LocalDate getExpectedAt() { return expectedAt; }
    public List<POLineJpaEntity> getLines() { return lines; }
}
