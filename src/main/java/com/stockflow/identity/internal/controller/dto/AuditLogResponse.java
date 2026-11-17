package com.stockflow.identity.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * One line of the audit trail as the System Admin reads it. {@code details} is returned as recorded - a JSON object the
 * audited operation chose to write, or for a failure the error with that object under {@code context} - and
 * {@code clientAddress} as the request came from: both may name a person, which is why only the System Admin holds
 * {@code platform-audit-logs:READ}.
 */
@Schema(name = "AuditLog", description = "One audited action: who, what, on which record, when, and how it ended")
public record AuditLogResponse(
        UUID id,
        Instant occurredAt,
        @Schema(description = "The user who acted; absent when the system acted (a scheduled job, a listener)")
        UUID actorId,
        @Schema(description = "The user's name at the time of the action, or 'system'") String actorName,
        @Schema(example = "UPDATE") String action,
        @Schema(example = "customer-price") String resourceType,
        String resourceId,
        @Schema(example = "SUCCESS", allowableValues = {"SUCCESS", "FAILURE"}) String outcome,
        @Schema(description = "Every entry of one request shares it, as do its log lines") String correlationId,
        String clientAddress,
        @Schema(description = "A JSON object, as recorded; absent when the operation recorded none") String details,
        @Schema(description = "Kept for the long retention (logins, grants, exports, deletions)")
        boolean securityRelevant) {
}
