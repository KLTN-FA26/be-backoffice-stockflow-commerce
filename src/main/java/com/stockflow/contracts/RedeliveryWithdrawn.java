package com.stockflow.contracts;

import java.time.Instant;
import java.util.UUID;

/**
 * Published by the <b>order</b> module when Sales cancel what a failed delivery left undelivered while the goods of
 * delivery failure case {@code caseId} were already released to the warehouse to be delivered again (WF-05 review F3).
 * The order's closure released the case's allocations in the cancelling transaction; nothing of them was picked, or
 * the cancellation would have been refused.
 *
 * <p>Fulfillment reacts by cancelling the pick task opened for that case - its lines point at allocations that are no
 * longer there, so the task could only fail. A line a picker confirmed in the window between the order's check and its
 * commit goes to the put-back queue, as the lines of a cancelled order do.</p>
 *
 * <p>Delivered at least once through the event publication registry; a task already cancelled stays cancelled.</p>
 */
public record RedeliveryWithdrawn(
        UUID orderId,
        String orderNumber,
        UUID caseId,
        UUID withdrawnBy,
        Instant withdrawnAt
) {
}
