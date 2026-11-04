package com.stockflow.customer.api;

import java.time.Instant;
import java.util.UUID;

/** One status a customer entered. {@code fromStatus} is null for its creation. */
public record CustomerStatusEntry(String fromStatus, String toStatus, String reason, UUID changedBy,
                                  Instant changedAt) {
}
