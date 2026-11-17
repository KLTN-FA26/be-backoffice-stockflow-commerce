package com.stockflow.order.api;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Goods of a shipment that came back, released to the warehouse to be delivered again (WF-05 fix plan, section 2;
 * kltn-docs 09 §4.3): one pick task picks them, as one picks a released delivery batch ({@link ReleasedBatch}) - a
 * second pick of an order and batch that were picked once already.
 *
 * @param caseId          the delivery failure case Sales decided: the pick names it
 * @param deliveryBatchId the batch the returned shipment carried; null for an order without a delivery schedule
 * @param holdIds         the allocations it stands for - only these are picked, not the order's other holds
 * @param units           the units it delivers again, per order line: what the carton check expects of the task
 */
public record RedeliveryToPick(UUID caseId, UUID deliveryBatchId, Set<UUID> holdIds, Map<UUID, Integer> units) {

    public RedeliveryToPick {
        holdIds = holdIds == null ? Set.of() : Set.copyOf(holdIds);
        units = units == null ? Map.of() : Map.copyOf(units);
    }
}
