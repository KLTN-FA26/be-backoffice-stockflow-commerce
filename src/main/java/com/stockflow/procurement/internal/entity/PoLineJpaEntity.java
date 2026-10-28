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
import org.hibernate.annotations.DynamicUpdate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/**
 * JPA mapping of a purchase order line (table {@code procurement.purchase_order_lines}).
 *
 * <p>What is ordered never changes once the order is submitted: an approved order is changed by an
 * amendment, which is a new revision, not an edit of this row. Afterwards only {@code status} moves
 * (receiving, closing, cancelling). The one exception is a SUBCONTRACT draft, whose single line is
 * resized before any revision exists ({@link #reviseDraftQuantity}), and a standard draft's lines are
 * edited by the buyer ({@link #reviseDraft}, SCRUM-462); {@code tg_purchase_order_lines_draft_content}
 * refuses either once the order has left DRAFT. {@code po_revision_id} is written by the revision snapshot with native SQL and is not
 * mapped. The amounts satisfy {@code ck_purchase_order_lines_amounts}: no line discount, so net is
 * the subtotal.</p>
 *
 * <p>The packing unit a line was ordered in ({@code uom_conversion_id} and {@code unit_qty}, V20261020000400) is
 * the domain line's {@code PackingUnit} (SCRUM-473), written on insert and by a draft revision, which may change it.
 * A SUBCONTRACT draft resized by {@link #reviseDraftQuantity} keeps its unit and recomputes {@code unit_qty}, since
 * {@code tg_purchase_order_lines_unit} refuses an {@code ordered_qty} that is not {@code unit_qty} times the unit's
 * factor.</p>
 *
 * <p>{@code @DynamicUpdate}: an UPDATE writes only the columns that changed. The amendment of a
 * subcontract order rewrites {@code ordered_qty} and {@code unit_qty} with native SQL; a full-row
 * UPDATE flushed from a copy loaded before it - a status change, say - would put the old pair back.</p>
 */
@Entity
@DynamicUpdate
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

    @Column(name = "unit_price", nullable = false, precision = 18, scale = 2)
    private BigDecimal unitPrice;

    @Column(name = "tax_rate", nullable = false, precision = 5, scale = 2)
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

    @Column(name = "note", length = 255)
    private String description;

    /** NULL = the base unit. */
    @Column(name = "uom_conversion_id")
    private UUID uomConversionId;

    @Column(name = "unit_qty")
    private Integer unitQuantity;

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
        writeUnit(line);
    }

    private void writeUnit(PoLine line) {
        this.uomConversionId = line.packingUnit() == null ? null : line.packingUnit().uomConversionId();
        this.unitQuantity = line.packingUnit() == null ? null : line.packingUnit().count();
    }

    void attachTo(PurchaseOrderJpaEntity order) {
        this.purchaseOrder = order;
    }

    /**
     * A new quantity for a line of a DRAFT order, amounts recomputed the way {@code PoLine} computes
     * them. Refused once the order has left DRAFT: from submission on, the revision snapshot is what
     * the approver approves, and a changed line would no longer match it. {@code unitFactor} is the
     * factor of the line's packing unit, ignored for a line in the base unit: a line in a carton stays
     * a whole number of cartons ({@link #wholeUnits}).
     */
    public void reviseDraftQuantity(int quantity, int unitFactor) {
        requireDraft();
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        if (uomConversionId != null) {
            this.unitQuantity = wholeUnits(purchaseOrder.getPoNumber(), quantity, unitFactor);
        }
        this.orderedQuantity = BigDecimal.valueOf(quantity);
        this.subtotal = unitPrice.multiply(orderedQuantity).setScale(2, RoundingMode.HALF_UP);
        this.net = subtotal;
        this.tax = subtotal.multiply(taxRate).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        this.total = subtotal.add(tax);
    }

    /**
     * The buyer's new quantity, unit, price, tax and description for a line of a DRAFT order (SCRUM-462,
     * SCRUM-473). The domain line already says which packing unit it is in and how many, so nothing is
     * worked out here.
     */
    public void reviseDraft(PoLine line) {
        requireDraft();
        this.unitPrice = line.unitPrice().amount();
        this.taxRate = line.taxRate();
        this.description = line.description();
        writeUnit(line);
        this.orderedQuantity = BigDecimal.valueOf(line.quantityOrdered());
        this.subtotal = line.subtotal();
        this.net = subtotal;
        this.tax = line.tax();
        this.total = line.total();
    }

    /**
     * How many units of {@code factor} base units {@code quantity} is. BR-09: the factor is a whole
     * number and there is no part of a carton, so anything else is {@code PO_LINE_UNIT_NOT_MULTIPLE}
     * rather than the generic 409 {@code tg_purchase_order_lines_unit} would turn it into.
     */
    public static int wholeUnits(String poNumber, int quantity, int factor) {
        return com.stockflow.procurement.internal.domain.PackingUnit.wholeUnits(poNumber, quantity, factor);
    }

    /** Taken off a draft after a revision froze it: the row stays for that revision ({@code ck_purchase_order_lines_withdrawn}). */
    public void withdraw() {
        requireDraft();
        this.status = PoLineStatus.WITHDRAWN;
    }

    private void requireDraft() {
        if (purchaseOrder.getStatus() != PurchaseOrderStatus.DRAFT) {
            throw new IllegalStateException("Line " + lineNo + " of " + purchaseOrder.getPoNumber() + " is frozen: the order is "
                    + purchaseOrder.getStatus());
        }
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
    public UUID getUomConversionId() { return uomConversionId; }
    public Integer getUnitQuantity() { return unitQuantity; }
}
