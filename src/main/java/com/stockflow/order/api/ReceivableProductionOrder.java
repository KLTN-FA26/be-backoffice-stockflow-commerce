package com.stockflow.order.api;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A production order as the warehouse receiving against it sees it (4A-5, kltn-docs 03 §3, §4.1): its code - which is
 * also the lot of everything received against it (BR-03) - where it is received, who makes it, and per line what it
 * orders and what was counted in so far, in cartons of the line's standard count.
 *
 * <p>{@code receivable} says whether goods can be received against it now (03 BR-01, BR-15): IN_PRODUCTION or
 * PARTIALLY_RECEIVED, or SENT / ACCEPTED when no print sample is to be approved first. When it cannot,
 * {@code notReceivableReason} says why, in words for the receiver; null otherwise. {@code status} is the production
 * order's status by name (17 §5.3).</p>
 */
public record ReceivableProductionOrder(UUID id, String code, String status, boolean receivable,
                                        String notReceivableReason, UUID receivingWarehouseId, UUID producerId,
                                        String producerName, List<Line> lines) {

    /**
     * One line: {@code cartons} ordered of {@code unitsPerCarton} each, {@code receivedCartons} of them counted in by
     * receipts confirmed so far (drafts not included).
     */
    public record Line(UUID id, UUID orderLineId, String sku, int cartons, int unitsPerCarton, int receivedCartons) {

        public Line {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(sku, "sku");
        }
    }

    public ReceivableProductionOrder {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(code, "code");
        lines = List.copyOf(lines);
    }

    /** The line with this id, if it is one of this production order's. */
    public Optional<Line> line(UUID lineId) {
        return lines.stream().filter(line -> line.id().equals(lineId)).findFirst();
    }
}
