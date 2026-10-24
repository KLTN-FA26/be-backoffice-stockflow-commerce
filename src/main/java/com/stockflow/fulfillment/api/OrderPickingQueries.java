package com.stockflow.fulfillment.api;

import com.stockflow.common.security.CurrentUser;

import java.util.Optional;
import java.util.UUID;

/**
 * An order's pick task and what happened on each line, for other modules (the assistant, ADR-0010): the detail
 * behind "why is this order on hold" when the reason is a short pick.
 *
 * <p>The same visibility as {@code GET /api/v1/fulfillment/tasks/{taskId}/pick-lines}: the caller must be allowed to
 * see the task (its warehouse, and oversight or assignment), or the call is refused exactly as that screen refuses it.
 * Call it inside the same guard, {@code @RequiresPermission(resource = "fulfillment-pick-lists", action = READ)}.</p>
 */
public interface OrderPickingQueries {

    /**
     * @return empty when the order has no pick task yet
     * @throws com.stockflow.common.error.BusinessException {@code OUT_OF_DATA_SCOPE} or {@code FORBIDDEN} when the
     *         caller may not see the task
     */
    Optional<OrderPicking> pickingOf(UUID orderId, CurrentUser actor);
}
