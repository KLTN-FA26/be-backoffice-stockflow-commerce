package com.stockflow.procurement.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import com.stockflow.procurement.internal.domain.PoLine;
import com.stockflow.procurement.internal.domain.PoLineStatus;
import com.stockflow.procurement.internal.domain.PurchaseOrderStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/**
 * JPA mapping of a purchase order line (table {@code procurement.purchase_order_lines}).
 *
 * <p>What is ordered never changes once the order is submitted: an approved order is changed by an
 * amendment, which is a new revision, not an edit of this row. Afterwards only {@code status} moves
 * (receiving, closing, cancelling). The one exception is a SUBCONTRACT draft, whose single line is
 * resized before any revision exists ({@link #reviseDraftQuantity}); quantity and amounts are
 * updatable for that and nothing else writes them. {@code po_revision_id} is written by the revision snapshot with native SQL and is not
 * mapped. The amounts satisfy {@code ck_purchase_order_lines_amounts}: no line discount, so net is
 * the subtotal.</p>
 */
@Entity
@Table(name = "purchase_order_lines", schema = "procurement")
public class PoLineJpaEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "po_id", nullable = false, updatable = false)
    private PurchaseOrderJpaEntity purchaseOrder;

    @Column(name = "line_no", nullable = false, updatable = false)
    private int lineNo;

    @Column(name = "inventory_item_id", nullable = false, updatable = false)
    private UUID inventoryItemId;

    @Column(name = "uom", nullable = false, length = 20, updatable = false)
    private String uom;

    @Column(name = "ordered_qty", nullable = false, precision = 18, scale = 3)
    private BigDecimal orderedQuantity;

    @Column(name = "unit_price", nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal unitPrice;

    @Column(name = "tax_rate", nullable = false, precision = 5, scale = 2, updatable = false)
    private BigDecimal taxRate;

    @Column(name = "discount_rate", nullable = false, precision = 5, scale = 2, updatable = false)
    private BigDecimal discountRate = BigDecimal.ZERO;

    @Column(name = "line_subtotal", nullable = false, precision = 18, scale = 2)
    private BigDecimal subtotal;

    @Column(name = "line_discount_amount", nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal discountAmount = BigDecimal.ZERO;

    @Column(name = "line_net", nullable = false, precision = 18, scale = 2)
    private BigDecimal net;

    @Column(name = "line_tax", nullable = false, precision = 18, scale = 2)
    private BigDecimal tax;

    @Column(name = "line_total", nullable = false, precision = 18, scale = 2)
    private BigDecimal total;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PoLineStatus status;

    @Column(name = "note", length = 255, updatable = false)
    private String description;

    protected PoLineJpaEntity() {
    }

    public PoLineJpaEntity(PoLine line) {
        super(line.id());
        this.lineNo = line.lineNo();
        this.inventoryItemId = line.inventoryItemId();
        this.uom = line.uom();
        this.orderedQuantity = BigDecimal.valueOf(line.quantityOrdered());
        this.unitPrice = line.unitPrice().amount();
        this.taxRate = line.taxRate();
        this.subtotal = line.subtotal();
        this.net = line.subtotal();
        this.tax = line.tax();
        this.total = line.total();
        this.status = line.status();
        this.description = line.description();
    }

    void attachTo(PurchaseOrderJpaEntity order) {
        this.purchaseOrder = order;
    }

    /**
     * A new quantity for a line of a DRAFT order, amounts recomputed the way {@code PoLine} computes
     * them. Refused once the order has left DRAFT: from submission on, the revision snapshot is what
     * the approver approves, and a changed line would no longer match it.
     */
    public void reviseDraftQuantity(int quantity) {
        if (purchaseOrder.getStatus() != PurchaseOrderStatus.DRAFT) {
            throw new IllegalStateException("Line " + lineNo + " of " + purchaseOrder.getPoNumber() + " is frozen: the order is "
                    + purchaseOrder.getStatus());
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        this.orderedQuantity = BigDecimal.valueOf(quantity);
        this.subtotal = unitPrice.multiply(orderedQuantity).setScale(2, RoundingMode.HALF_UP);
        this.net = subtotal;
        this.tax = subtotal.multiply(taxRate).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        this.total = subtotal.add(tax);
    }

    public void setStatus(PoLineStatus status) {
        this.status = status;
    }

    public int getLineNo() { return lineNo; }
    public UUID getInventoryItemId() { return inventoryItemId; }
    public String getUom() { return uom; }
    public BigDecimal getOrderedQuantity() { return orderedQuantity; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public BigDecimal getTaxRate() { return taxRate; }
    public BigDecimal getSubtotal() { return subtotal; }
    public BigDecimal getTax() { return tax; }
    public BigDecimal getTotal() { return total; }
    public PoLineStatus getStatus() { return status; }
    public String getDescription() { return description; }
}
