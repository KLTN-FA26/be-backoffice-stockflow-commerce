package com.stockflow.procurement.internal.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A purchase order as receiving sees it ({@code procurement.purchase_orders},
 * {@code purchase_order_lines}). Not the {@code PurchaseOrder} aggregate: receiving changes only what
 * receipt progress changes — the line and header status, and an event on the order's timeline — and
 * reads nothing else, so it reads the rows directly.
 *
 * @param tolerancePercent the supplier's over-receipt tolerance (BR-02), zero when none is set
 * @param supplierRejected the supplier refused the order (#36): nothing is received against it
 */
public record ReceivingPurchaseOrder(
        UUID id,
        String number,
        Status status,
        UUID supplierId,
        UUID warehouseId,
        UUID activeRevisionId,
        BigDecimal tolerancePercent,
        boolean supplierRejected,
        List<Line> lines
) {

    public enum Status { DRAFT, PENDING_APPROVAL, APPROVED, CONFIRMED, PARTIALLY_RECEIVED, RECEIVED, CLOSED, CANCELLED }

    public enum LineStatus { OPEN, PARTIALLY_RECEIVED, RECEIVED, CLOSED, CANCELLED }

    public record Line(UUID id, int lineNo, UUID inventoryItemId, BigDecimal orderedQuantity, LineStatus status) {

        /** Most that may ever be received against the line: ordered × (1 + tolerance / 100), rounded down. */
        public int receivableLimit(BigDecimal tolerancePercent) {
            return orderedQuantity.multiply(BigDecimal.ONE.add(tolerancePercent.movePointLeft(2)))
                    .setScale(0, RoundingMode.FLOOR).intValueExact();
        }

        public boolean open() {
            return status == LineStatus.OPEN || status == LineStatus.PARTIALLY_RECEIVED;
        }
    }

    public ReceivingPurchaseOrder {
        Objects.requireNonNull(id, "id");
        tolerancePercent = tolerancePercent == null ? BigDecimal.ZERO : tolerancePercent;
        lines = List.copyOf(lines);
    }

    /** BR-01: goods are received only against a confirmed order the supplier did not refuse. */
    public boolean receivable() {
        return (status == Status.CONFIRMED || status == Status.PARTIALLY_RECEIVED) && !supplierRejected;
    }

    public Optional<Line> line(UUID lineId) {
        return lines.stream().filter(l -> l.id().equals(lineId)).findFirst();
    }

    /** A line's status once {@code received} units have been counted in against it in total. */
    public static LineStatus lineStatusFor(Line line, int received) {
        if (!line.open()) {
            return line.status();
        }
        if (received <= 0) {
            return LineStatus.OPEN;
        }
        return BigDecimal.valueOf(received).compareTo(line.orderedQuantity()) >= 0
                ? LineStatus.RECEIVED : LineStatus.PARTIALLY_RECEIVED;
    }

    /**
     * The order's status after receiving, given every line's new status: RECEIVED once nothing is left
     * open, PARTIALLY_RECEIVED otherwise. A closed or cancelled line counts as nothing left to receive.
     */
    public Status statusAfter(Map<UUID, LineStatus> lineStatuses) {
        boolean anyOpen = lines.stream()
                .map(l -> lineStatuses.getOrDefault(l.id(), l.status()))
                .anyMatch(s -> s == LineStatus.OPEN || s == LineStatus.PARTIALLY_RECEIVED);
        return anyOpen ? Status.PARTIALLY_RECEIVED : Status.RECEIVED;
    }
}
