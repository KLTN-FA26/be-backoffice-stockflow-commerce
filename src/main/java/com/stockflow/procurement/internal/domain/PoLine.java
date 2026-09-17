package com.stockflow.procurement.internal.domain;

import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;

import java.util.Objects;
import java.util.UUID;

/**
 * One line of a {@link PurchaseOrder}. An entity inside the aggregate, no repository of its own —
 * same shape as {@code order.internal.domain.OrderLine}.
 *
 * <p>{@code quantityReceived} advances only through {@link #receive}, called by
 * {@link PurchaseOrder#receiveGoods} (SCRUM-116/WBS 3.2.4.1) — never set directly from outside the
 * aggregate.</p>
 */
public final class PoLine {

    private final UUID id;
    private final Sku sku;
    private final String description;
    private final int quantityOrdered;
    private int quantityReceived;
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

    /**
     * Package-private: only {@link PurchaseOrder#receiveGoods} may advance this.
     *
     * <p>No over-receipt tolerance is modeled (BR-RCP-001 in the full state machine) — a receipt
     * cannot push {@code quantityReceived} past {@code quantityOrdered}. Flagged as a gap, not
     * silently clamped, since silently capping would make a warehouse's real over-shipment vanish
     * from the record instead of surfacing it.</p>
     */
    void receive(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Received quantity must be positive");
        }
        if (quantity > openQuantity()) {
            throw new IllegalArgumentException(
                    "Cannot receive %d for line %s: only %d still open".formatted(
                            quantity, id, openQuantity()));
        }
        this.quantityReceived += quantity;
    }

    public UUID id() { return id; }
    public Sku sku() { return sku; }
    public String description() { return description; }
    public int quantityOrdered() { return quantityOrdered; }
    public int quantityReceived() { return quantityReceived; }
    public Money unitPrice() { return unitPrice; }
}
