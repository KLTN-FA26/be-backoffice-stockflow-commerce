package com.stockflow.customer.api;

import java.util.UUID;

public record UpdateCustomerCommand(
        UUID customerId,
        UUID actorUserId,
        boolean privileged,
        String fullName,
        String phone,
        long expectedVersion,
        /* Staff only (Sales records the business identity, kltn-docs 18 §3); null leaves it unchanged. */
        String taxCode
) {
    public UpdateCustomerCommand(UUID customerId, UUID actorUserId, boolean privileged, String fullName,
                                 String phone, long expectedVersion) {
        this(customerId, actorUserId, privileged, fullName, phone, expectedVersion, null);
    }
}
