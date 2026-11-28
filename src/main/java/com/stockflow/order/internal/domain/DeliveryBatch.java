package com.stockflow.order.internal.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One delivery batch of a contract's schedule (kltn-docs 14 BR-23): its number, its delivery window - from ≤ to; a
 * fixed day is the same day twice; none yet on a draft until Sales enters it - and the cartons of the order's lines it
 * delivers. Every batch goes to the order's one address (D38).
 */
public record DeliveryBatch(UUID id, int seq, LocalDate windowStart, LocalDate windowEnd, List<Line> lines) {

    /** Cartons of one order line in the batch. */
    public record Line(UUID id, UUID orderLineId, int cartons) {

        public Line {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(orderLineId, "orderLineId");
        }
    }

    public DeliveryBatch {
        Objects.requireNonNull(id, "id");
        lines = List.copyOf(lines);
        if ((windowStart == null) != (windowEnd == null)) {
            throw new IllegalArgumentException("A delivery window has both its days or neither");
        }
    }

    public boolean hasWindow() {
        return windowStart != null;
    }

    /**
     * This batch less {@code dropped} cartons per order line (kltn-docs 17 §4.14 step 58, D45): a line left with none is
     * gone; a batch left with none carries no line (D52). Never below zero.
     */
    public DeliveryBatch less(java.util.Map<UUID, Integer> dropped) {
        if (dropped == null || dropped.isEmpty()) {
            return this;
        }
        List<Line> kept = lines.stream()
                .map(l -> new Line(l.id(), l.orderLineId(), l.cartons() - dropped.getOrDefault(l.orderLineId(), 0)))
                .filter(l -> l.cartons() > 0).toList();
        return new DeliveryBatch(id, seq, windowStart, windowEnd, kept);
    }

    /** The cartons of {@code orderLineId} in this batch; 0 when it delivers none of it. */
    public int cartonsOf(UUID orderLineId) {
        return lines.stream().filter(l -> l.orderLineId().equals(orderLineId)).mapToInt(Line::cartons).sum();
    }
}
