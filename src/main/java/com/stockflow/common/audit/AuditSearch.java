package com.stockflow.common.audit;

import com.stockflow.common.api.FieldError;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What to look for in the audit trail ({@link AuditHistory#search}); every field optional, a null one matching every
 * entry. Each field the investigation asks first is backed by an index of {@code platform.audit_log}: who
 * ({@code ix_audit_actor_time}), which record ({@code ix_audit_resource}), when ({@code ix_audit_occurred}), which
 * request ({@code ix_audit_correlation}).
 *
 * @param resourceType     as recorded by {@link Auditable#resourceType()}, exactly: {@code "customer-price"}
 * @param resourceId       as recorded by {@link Auditable#resourceId()}, exactly
 * @param from             entries at or after this moment
 * @param to               entries before this moment; after {@code from} when both are given
 * @param correlationId    every entry one request produced
 * @param securityRelevant only the entries kept for the long retention ({@link AuditAction#isSecurityRelevant()}), or
 *                         only the others
 * @throws BusinessException {@code VALIDATION_FAILED} for a {@code to} not after {@code from}
 */
public record AuditSearch(UUID actorId, String resourceType, String resourceId, AuditAction action,
                          AuditEntry.Outcome outcome, Instant from, Instant to, String correlationId,
                          Boolean securityRelevant) {

    public AuditSearch {
        resourceType = blankToNull(resourceType);
        resourceId = blankToNull(resourceId);
        correlationId = blankToNull(correlationId);
        if (from != null && to != null && !to.isAfter(from)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "The end of the period is not after its start",
                    List.of(FieldError.of("to", "must be after from")));
        }
    }

    /** Everything: no filter. */
    public static AuditSearch everything() {
        return new AuditSearch(null, null, null, null, null, null, null, null, null);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
