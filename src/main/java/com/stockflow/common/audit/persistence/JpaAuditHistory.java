package com.stockflow.common.audit.persistence;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.audit.AuditEntry;
import com.stockflow.common.audit.AuditHistory;
import com.stockflow.common.audit.AuditRecord;
import com.stockflow.common.audit.AuditSearch;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.persistence.Specs;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Database-backed {@link AuditHistory}, reading the same table {@link JpaAuditTrail} writes. */
@Component
class JpaAuditHistory implements AuditHistory {

    /** Newest first; the id (time-ordered, UUIDv7) breaks a tie, so paging is stable. */
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("occurredAt"), Sort.Order.desc("id"));

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

    /**
     * One Specification of the filters given - a null filter matches everything ({@link Specs#eq}), which is right
     * here: every one is optional, and the caller's permission, not a data scope, decides who searches the trail.
     */
    @Override
    @Transactional(readOnly = true)
    public PageResponse<AuditRecord> search(AuditSearch filter, int page, int size) {
        AuditSearch f = filter == null ? AuditSearch.everything() : filter;
        List<Specification<AuditLogEntity>> where = new ArrayList<>();
        where.add(Specs.eq("actorId", f.actorId()));
        where.add(Specs.eq("resourceType", f.resourceType()));
        where.add(Specs.eq("resourceId", f.resourceId()));
        where.add(Specs.eq("action", f.action()));
        where.add(Specs.eq("outcome", f.outcome()));
        where.add(Specs.eq("correlationId", f.correlationId()));
        where.add(Specs.eq("securityRelevant", f.securityRelevant()));
        where.add(atOrAfter(f.from()));
        where.add(before(f.to()));
        return Pages.toResponse(repository.findAll(Specification.allOf(where), Pages.of(page, size, NEWEST_FIRST))
                .map(JpaAuditHistory::toRecord));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AuditRecord> find(UUID id) {
        return id == null ? Optional.empty() : repository.findById(id).map(JpaAuditHistory::toRecord);
    }

    private static Specification<AuditLogEntity> atOrAfter(Instant from) {
        return from == null ? Specs.all()
                : (root, query, cb) -> cb.greaterThanOrEqualTo(root.<Instant>get("occurredAt"), from);
    }

    private static Specification<AuditLogEntity> before(Instant to) {
        return to == null ? Specs.all() : (root, query, cb) -> cb.lessThan(root.<Instant>get("occurredAt"), to);
    }

    private static AuditRecord toRecord(AuditLogEntity entity) {
        return new AuditRecord(entity.getId(), toEntry(entity), entity.isSecurityRelevant());
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
