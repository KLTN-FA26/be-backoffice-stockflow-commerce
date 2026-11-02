package com.stockflow.notification.api;

import java.util.UUID;

/**
 * A customer as a message addresses it: the name to greet (the company, for a business customer) and the address to
 * send to, as the customer module holds them. Either may be null or malformed in old data; notification checks the
 * address before it sends and records a skip when it is unusable.
 *
 * <p>{@link #toString()} names the customer id only: an address in a log line is a leak.</p>
 */
public record CustomerContact(UUID customerId, String name, String email) {

    @Override
    public String toString() {
        return "CustomerContact[customerId=" + customerId + "]";
    }
}
