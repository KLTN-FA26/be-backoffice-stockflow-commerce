package com.stockflow.common.audit;

import java.time.Instant;
import java.util.UUID;

/**
 * One line of the audit trail.
 *
 * @param actorId       the user who acted, or null for a scheduled job acting as the system
 * @param actorName     denormalised on purpose - a username at the time of the action. Joining to
 *                      the user table later would show today's name, and a renamed or deleted user
 *                      would make the entry unreadable. An audit record must stay true to the
 *                      moment it describes.
 * @param outcome       whether the action succeeded; failures are recorded too
 * @param correlationId ties the entry to the request's log lines and trace
 * @param details       free-form JSON for what the action changed. Deliberately not a structured
 *                      before/after diff: capturing one generically means serialising whole
 *                      entities, which is slow and puts personal data into a table with a long
 *                      retention. Callers put in what matters.
 */
public record AuditEntry(
        UUID actorId,
        String actorName,
        AuditAction action,
        String resourceType,
        String resourceId,
        Outcome outcome,
        String correlationId,
        String clientAddress,
        String details,
        Instant occurredAt
) {

    public enum Outcome {
        SUCCESS,
        /** The operation threw. The details carry the exception type and message. */
        FAILURE
    }
}
