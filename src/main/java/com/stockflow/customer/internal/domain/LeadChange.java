package com.stockflow.customer.internal.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One entry of a lead's history, the row {@code customer.lead_history} keeps (append-only): a change of its status or
 * Sales, or a later submission of the form by the same business (finding P5), which changes neither.
 *
 * @param fromStatus null for the submission
 * @param changedBy  who did it; null for the system (the public form, the deactivation listener)
 * @param kind       {@link Kind#CHANGE}, or {@link Kind#RESUBMITTED} with the later form in {@code note}
 */
public record LeadChange(UUID id, UUID leadId, LeadStatus fromStatus, LeadStatus toStatus, UUID fromUserId,
                         UUID toUserId, String note, UUID changedBy, Instant changedAt, Kind kind) {

    /** What the entry records; the same values as {@code ck_lead_history_kind} (V20270128000450). */
    public enum Kind { CHANGE, RESUBMITTED }

    public LeadChange {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(leadId, "leadId");
        Objects.requireNonNull(toStatus, "toStatus");
        Objects.requireNonNull(changedAt, "changedAt");
        Objects.requireNonNull(kind, "kind");
    }

    /** A change of status or Sales. */
    public LeadChange(UUID id, UUID leadId, LeadStatus fromStatus, LeadStatus toStatus, UUID fromUserId,
                      UUID toUserId, String note, UUID changedBy, Instant changedAt) {
        this(id, leadId, fromStatus, toStatus, fromUserId, toUserId, note, changedBy, changedAt, Kind.CHANGE);
    }
}
