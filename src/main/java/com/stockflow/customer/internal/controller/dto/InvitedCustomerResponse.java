package com.stockflow.customer.internal.controller.dto;

import java.time.Instant;

/**
 * The customer and the invitation just sent. {@code delivery} is SENT or FAILED: the customer exists
 * either way, and a FAILED e-mail is recovered by resending. The link itself is never returned.
 */
public record InvitedCustomerResponse(CustomerResponse customer, Invitation invitation) {

    public record Invitation(Instant issuedAt, Instant expiresAt, String delivery) {
    }
}
