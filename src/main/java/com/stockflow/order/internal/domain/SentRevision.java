package com.stockflow.order.internal.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * One revision of a contract as it was sent to the customer: the PDF kept, when and by whom. A change request of the
 * customer ends a revision ({@link SalesContract#requestChanges}); the next send starts the next one.
 */
public record SentRevision(int revision, String documentKey, Instant sentAt, UUID sentBy) {
}
