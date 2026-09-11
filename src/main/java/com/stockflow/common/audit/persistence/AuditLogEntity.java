package com.stockflow.common.audit.persistence;

import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.AuditEntry;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of {@code platform.audit_log}.
 *
 * <p>Append-only by convention and by the absence of any setter: there is no {@code update} method
 * and no {@code @Version}, because an audit entry that can be edited is not an audit entry. The
 * only writes are inserts and the retention purge.</p>
 *
 * <p>Not a {@link com.stockflow.common.persistence.BaseEntity}: no optimistic lock is needed for a
 * row that is never updated, and the audit columns would be circular — the actor and timestamp are
 * already the point of the table.</p>
 */
@Entity
@Table(
        name = "audit_log",
        schema = "platform",
        indexes = {
                // "what did this user do" - the first question in any investigation
                @Index(name = "ix_audit_actor_time", columnList = "actor_id, occurred_at"),
                // "who touched this order" - the second one
                @Index(name = "ix_audit_resource", columnList = "resource_type, resource_id"),
                // drives the retention purge and every time-range report
                @Index(name = "ix_audit_occurred", columnList = "occurred_at"),
                // lets a failing log line be joined to the actions it produced
                @Index(name = "ix_audit_correlation", columnList = "correlation_id")
        })
class AuditLogEntity implements Persistable<UUID> {
    /**
     * Application-assigned ids mean Spring Data cannot use "is the id null" to tell a new row from
     * an existing one, so {@code save()} would take the {@code merge()} path and issue a
     * {@code SELECT} before every {@code INSERT}. On every audited call that is a wasted round trip on
     * a hot write path. See {@code BaseEntity} for the full reasoning — this entity does not extend
     * it (it needs neither the audit columns nor optimistic locking), so it carries its own flag.
     */
    @Transient
    private transient boolean persisted = false;

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return !persisted;
    }

    @PostPersist
    @PostLoad
    void markPersisted() {
        this.persisted = true;
    }


    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Null when the system acted - a scheduled job, a migration, an event listener. */
    @Column(name = "actor_id")
    private UUID actorId;

    /** The name as it was at the time. Never joined back to the user table; see {@link AuditEntry}. */
    @Column(name = "actor_name", length = 100)
    private String actorName;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 20)
    private AuditAction action;

    @Column(name = "resource_type", nullable = false, length = 64)
    private String resourceType;

    @Column(name = "resource_id", length = 128)
    private String resourceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 10)
    private AuditEntry.Outcome outcome;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @Column(name = "client_address", length = 45)
    private String clientAddress;

    @Column(name = "details", columnDefinition = "text")
    private String details;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    /**
     * Denormalised from {@link AuditAction#isSecurityRelevant()} at write time.
     *
     * <p>Stored rather than computed in the purge query for one reason: if the classification of an
     * action changes in a future release, entries already written keep the retention they were
     * created with. A purge that recomputed it could delete last year's login records because
     * somebody reclassified {@code LOGIN} — exactly the data an investigation needs and cannot get
     * back.</p>
     */
    @Column(name = "security_relevant", nullable = false)
    private boolean securityRelevant;

    protected AuditLogEntity() {
    }

    AuditLogEntity(UUID id, AuditEntry entry) {
        this.id = id;
        this.actorId = entry.actorId();
        this.actorName = truncate(entry.actorName(), 100);
        this.action = entry.action();
        this.resourceType = truncate(entry.resourceType(), 64);
        this.resourceId = truncate(entry.resourceId(), 128);
        this.outcome = entry.outcome();
        this.correlationId = truncate(entry.correlationId(), 64);
        this.clientAddress = truncate(entry.clientAddress(), 45);
        this.details = entry.details();
        this.occurredAt = entry.occurredAt();
        this.securityRelevant = entry.action().isSecurityRelevant();
    }

    /**
     * Keeps an over-long value from failing the insert.
     *
     * <p>A truncated audit entry is worth far more than none: the alternative is a
     * {@code DataIntegrityViolationException} inside the audit path, which
     * {@link com.stockflow.common.audit.AuditTrail} promises will never surface — so it would be
     * swallowed and the entry lost entirely.</p>
     */
    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
