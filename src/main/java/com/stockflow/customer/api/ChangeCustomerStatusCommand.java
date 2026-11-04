package com.stockflow.customer.api;

import java.util.UUID;

/**
 * A manager's status change. {@code reason} is required to reopen a closed (INACTIVE) customer and
 * kept in the status history; {@code actorUserId} is the manager.
 */
public record ChangeCustomerStatusCommand(UUID customerId, String status, String reason, UUID actorUserId) {
}
