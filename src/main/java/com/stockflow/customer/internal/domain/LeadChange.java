package com.stockflow.customer.internal.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One change of a lead's status or Sales: the row {@code customer.lead_history} keeps (append-only).
 *
 * @param fromStatus null for the submission
 * @param changedBy  who did it; null for the system (the public form, the deactivation listener)
 */
public record LeadChange(UUID id, UUID leadId, LeadStatus fromStatus, LeadStatus toStatus, UUID fromUserId,
                         UUID toUserId, String note, UUID changedBy, Instant changedAt) {

    public LeadChange {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(leadId, "leadId");
        Objects.requireNonNull(toStatus, "toStatus");
        Objects.requireNonNull(changedAt, "changedAt");
    }
}
