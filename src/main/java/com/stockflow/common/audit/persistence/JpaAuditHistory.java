package com.stockflow.common.audit.persistence;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.audit.AuditEntry;
import com.stockflow.common.audit.AuditHistory;
import com.stockflow.common.persistence.Pages;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Database-backed {@link AuditHistory}, reading the same table {@link JpaAuditTrail} writes. */
@Component
class JpaAuditHistory implements AuditHistory {

    private final AuditLogJpaRepository repository;

    JpaAuditHistory(AuditLogJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<AuditEntry> forResource(String resourceType, String resourceId, int page, int size) {
        Page<AuditEntry> result = repository
                .findByResourceTypeAndResourceIdOrderByOccurredAtDesc(
                        resourceType, resourceId, Pages.of(page, size))
                .map(JpaAuditHistory::toEntry);
        return Pages.toResponse(result);
    }

    private static AuditEntry toEntry(AuditLogEntity entity) {
        return new AuditEntry(
                entity.getActorId(),
                entity.getActorName(),
                entity.getAction(),
                entity.getResourceType(),
                entity.getResourceId(),
                entity.getOutcome(),
                entity.getCorrelationId(),
                entity.getClientAddress(),
                entity.getDetails(),
                entity.getOccurredAt());
    }
}
