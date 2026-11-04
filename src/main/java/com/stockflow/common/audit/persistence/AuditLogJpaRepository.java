package com.stockflow.common.audit.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

/** Writes and purges {@code platform.audit_log}. There is deliberately no update method. */
interface AuditLogJpaRepository extends JpaRepository<AuditLogEntity, UUID> {

    /**
     * SCRUM-86 (WBS 3.1.8.2): the read side of the trail — "what happened to this record, in
     * order" — backed by {@code ix_audit_resource (resource_type, resource_id)}, the same index
     * {@link AuditLogEntity}'s own javadoc calls out for exactly this question.
     */
    Page<AuditLogEntity> findByResourceTypeAndResourceIdOrderByOccurredAtDesc(
            String resourceType, String resourceId, Pageable pageable);

    /**
     * Retention purge, in bounded batches and respecting the security classification.
     *
     * <p>Two cut-offs, because the two kinds of entry answer different questions. Routine business
     * entries are read within weeks; login, permission and export records are what an investigation
     * needs a year later. Purging both on the same schedule means either keeping everything far too
     * long or destroying the evidence.</p>
     */
    @Modifying
    @Query(value = """
            delete from platform.audit_log
            where id in (
                select id from platform.audit_log
                where (security_relevant = false and occurred_at < :routineCutoff)
                   or (security_relevant = true  and occurred_at < :securityCutoff)
                limit :batchSize
            )
            """, nativeQuery = true)
    int purge(@Param("routineCutoff") Instant routineCutoff,
              @Param("securityCutoff") Instant securityCutoff,
              @Param("batchSize") int batchSize);
}
