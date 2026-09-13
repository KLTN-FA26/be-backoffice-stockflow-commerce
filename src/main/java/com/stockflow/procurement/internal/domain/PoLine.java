package com.stockflow.procurement.internal.domain;

import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;

import java.util.Objects;
import java.util.UUID;

/**
 * One line of a {@link PurchaseOrder}. An entity inside the aggregate, no repository of its own —
 * same shape as {@code order.internal.domain.OrderLine}.
 *
 * <p>{@code quantityReceived} is carried (not always zero) because rehydrating an existing PO must
 * reflect receipts already posted, even though nothing in this story (SCRUM-113, creation only)
 * ever sets it above zero — see SCRUM-116 for the transition that does.</p>
 */
public final class PoLine {

    private final UUID id;
    private final Sku sku;
    private final String description;
    private final int quantityOrdered;
    private final int quantityReceived;
    private final Money unitPrice;

    public PoLine(UUID id, Sku sku, String description, int quantityOrdered, int quantityReceived,
                 Money unitPrice) {
        this.id = Objects.requireNonNull(id, "id");
        this.sku = Objects.requireNonNull(sku, "sku");
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
        if (unitPrice.amount().signum() < 0) {
            throw new IllegalArgumentException("unitPrice must not be negative");
        }
    }

    static PoLine draft(UUID id, Sku sku, String description, int quantityOrdered, Money unitPrice) {
        return new PoLine(id, sku, description, quantityOrdered, 0, unitPrice);
    }

    public Money lineTotal() {
        return unitPrice.times(quantityOrdered);
    }

    public int openQuantity() {
        return quantityOrdered - quantityReceived;
    }

    public UUID id() { return id; }
    public Sku sku() { return sku; }
    public String description() { return description; }
    public int quantityOrdered() { return quantityOrdered; }
    public int quantityReceived() { return quantityReceived; }
    public Money unitPrice() { return unitPrice; }
}
