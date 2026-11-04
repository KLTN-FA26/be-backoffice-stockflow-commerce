package com.stockflow.fulfillment.api;

import java.util.List;
import java.util.UUID;

/** Someone a task can be assigned to: active, in an operational warehouse role, in the task's warehouse. */
public record FulfillmentAssignee(UUID userId, String displayName, List<String> roles) {

    public FulfillmentAssignee {
        roles = roles == null ? List.of() : List.copyOf(roles);
    }
}
