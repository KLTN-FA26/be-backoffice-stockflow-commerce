package com.stockflow.procurement.internal.domain;

import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.UUID;

/**
 * One line of a {@link PurchaseOrder}: so many units of one inventory item at one price. An entity
 * inside the aggregate, no repository of its own.
 *
 * <p>A line orders an inventory item ({@code purchase_order_lines.inventory_item_id}); its SKU is
 * carried alongside because that is what people and the supplier read. {@code quantityReceived} is
 * not stored on the line: it is the sum of the confirmed goods receipts against it, read with the
 * order. The amounts follow {@code ck_purchase_order_lines_amounts}: no line discount yet, tax at
 * {@code taxRate} percent of the net, rounded to the cent.</p>
 */
public final class PoLine {

    private final UUID id;
    private final int lineNo;
    private final UUID inventoryItemId;
    private final Sku sku;
    private final String uom;
    private final String description;
    private final int quantityOrdered;
    private final int quantityReceived;
    private final Money unitPrice;
    private final BigDecimal taxRate;
    private PoLineStatus status;

    public PoLine(UUID id, int lineNo, UUID inventoryItemId, Sku sku, String uom, String description,
                  int quantityOrdered, int quantityReceived, Money unitPrice, BigDecimal taxRate, PoLineStatus status) {
        this.id = Objects.requireNonNull(id, "id");
        if (lineNo <= 0) {
            throw new IllegalArgumentException("lineNo must be positive");
        }
        this.lineNo = lineNo;
        this.inventoryItemId = Objects.requireNonNull(inventoryItemId, "inventoryItemId");
        this.sku = Objects.requireNonNull(sku, "sku");
        this.uom = uom == null ? "EACH" : uom;
        this.description = description;
        if (quantityOrdered <= 0) {
            throw new IllegalArgumentException("quantityOrdered must be positive");
        }
        this.quantityOrdered = quantityOrdered;
        if (quantityReceived < 0) {
            throw new IllegalArgumentException("quantityReceived must not be negative");
        }
        this.quantityReceived = quantityReceived;
        this.unitPrice = Objects.requireNonNull(unitPrice, "unitPrice");
        if (unitPrice.amount().signum() <= 0) {
            throw new IllegalArgumentException("unitPrice must be positive");
        }
        this.taxRate = taxRate == null ? BigDecimal.ZERO : taxRate;
        if (this.taxRate.signum() < 0 || this.taxRate.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new IllegalArgumentException("taxRate must be between 0 and 100");
        }
        this.status = Objects.requireNonNull(status, "status");
    }

    static PoLine draft(UUID id, int lineNo, UUID inventoryItemId, Sku sku, String uom, String description,
                        int quantityOrdered, Money unitPrice, BigDecimal taxRate) {
        return new PoLine(id, lineNo, inventoryItemId, sku, uom, description, quantityOrdered, 0, unitPrice, taxRate,
                PoLineStatus.OPEN);
    }

    /** Quantity × price, before tax. */
    public BigDecimal subtotal() {
        return unitPrice.amount().multiply(BigDecimal.valueOf(quantityOrdered)).setScale(2, RoundingMode.HALF_UP);
    }

    public BigDecimal tax() {
        return subtotal().multiply(taxRate).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    public BigDecimal total() {
        return subtotal().add(tax());
    }

    public int openQuantity() {
        return Math.max(0, quantityOrdered - quantityReceived);
    }

    /** What is still open goes with the order: cancelled, or closed short. */
    void settle(PoLineStatus closing) {
        if (status == PoLineStatus.OPEN || status == PoLineStatus.PARTIALLY_RECEIVED) {
            status = closing;
        }
    }

    public UUID id() { return id; }
    public int lineNo() { return lineNo; }
    public UUID inventoryItemId() { return inventoryItemId; }
    public Sku sku() { return sku; }
    public String uom() { return uom; }
    public String description() { return description; }
    public int quantityOrdered() { return quantityOrdered; }
    public int quantityReceived() { return quantityReceived; }
    public Money unitPrice() { return unitPrice; }
    public BigDecimal taxRate() { return taxRate; }
    public PoLineStatus status() { return status; }
}
