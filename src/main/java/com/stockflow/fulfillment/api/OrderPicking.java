package com.stockflow.fulfillment.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * An order's pick task, as {@link OrderPickingQueries} returns it.
 *
 * @param status the task's pick status ({@code OPEN}, {@code PICKING}, {@code PICKED}, ...)
 */
public record OrderPicking(UUID taskId, String status, Instant assignedAt, Instant pickedAt, List<Line> lines) {

    public OrderPicking {
        lines = List.copyOf(lines);
    }

    /**
     * One pick line.
     *
     * @param status   the line's status, {@code SHORT} when the picker found less than allocated
     * @param shortQty units the picker could not find
     */
    public record Line(String sku, String locationCode, String lotNumber, int allocatedQty, int pickedQty,
                       int shortQty, int putBackQty, String status) {
    }
}
