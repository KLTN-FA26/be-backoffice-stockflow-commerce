package com.stockflow.customer.api;

import java.util.UUID;

public record UpdateCustomerCommand(
        UUID customerId,
        UUID actorUserId,
        boolean privileged,
        String fullName,
        String phone,
        long expectedVersion
) {
}
