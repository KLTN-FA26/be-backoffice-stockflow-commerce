package com.stockflow.order.api;

import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;

import java.util.List;
import java.util.UUID;

/**
 * Intent: turn a cart into an order.
 *
 * <p>Records with compact-constructor validation, so an instance that exists is structurally
 * valid. The caller cannot hand the service a command with zero lines and discover the problem
 * three layers down.</p>
 */
public record PlaceOrderCommand(UUID requestId, UUID customerId, List<Line> lines) {

    public PlaceOrderCommand {
        if (requestId == null) throw new IllegalArgumentException("requestId is required");
        if (customerId == null) throw new IllegalArgumentException("customerId is required");
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("An order needs at least one line");
        }
        lines = List.copyOf(lines);
    }

    /**
     * One line.
     *
     * @param designSnapshotId set only for print-on-demand items; identifies the frozen design the
     *                         customer approved, so a later edit to the design cannot change what
     *                         was ordered
     */
    public record Line(Sku sku, int quantity, Money unitPrice, UUID designSnapshotId) {

        public Line {
            if (sku == null) throw new IllegalArgumentException("sku is required");
            if (quantity < 1) throw new IllegalArgumentException("quantity must be at least 1");
            if (unitPrice == null) throw new IllegalArgumentException("unitPrice is required");
            if (unitPrice.isNegative()) {
                throw new IllegalArgumentException("unitPrice must not be negative");
            }
        }
    }
}
