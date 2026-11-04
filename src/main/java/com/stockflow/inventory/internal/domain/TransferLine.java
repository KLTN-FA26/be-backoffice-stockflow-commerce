package com.stockflow.inventory.internal.domain;

import com.stockflow.common.domain.Sku;

import java.util.Objects;
import java.util.UUID;

/**
 * One SKU (and lot, when the request names one) on a transfer order. {@code shipped} is what left
 * the source on dispatch: from then on those units are in transit, owned by the transfer, and
 * counted in no warehouse's stock (BR-03).
 */
public final class TransferLine {

    private final UUID id;
    private final int lineNo;
    private final Sku sku;
    private final String lotNumber;
    private final int requested;
    private int shipped;

    public TransferLine(UUID id, int lineNo, Sku sku, String lotNumber, int requested, int shipped) {
        this.id = Objects.requireNonNull(id, "id");
        this.sku = Objects.requireNonNull(sku, "sku");
        this.lotNumber = lotNumber == null || lotNumber.isBlank() ? null : lotNumber.trim();
        if (lineNo <= 0 || requested <= 0 || shipped < 0 || shipped > requested) {
            throw new IllegalArgumentException("Invalid transfer line %d: requested %d, shipped %d"
                    .formatted(lineNo, requested, shipped));
        }
        this.lineNo = lineNo;
        this.requested = requested;
        this.shipped = shipped;
    }

    void ship(int quantity) {
        if (quantity < 0 || quantity > requested) {
            throw new IllegalArgumentException("Line %d ships %d of %d requested".formatted(lineNo, quantity, requested));
        }
        this.shipped = quantity;
    }

    public UUID id() { return id; }
    public int lineNo() { return lineNo; }
    public Sku sku() { return sku; }
    public String lotNumber() { return lotNumber; }
    public int requested() { return requested; }
    public int shipped() { return shipped; }
}
