package com.stockflow.common.idempotency.persistence;

import com.stockflow.common.idempotency.IdempotencyRecord;
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
import jakarta.persistence.UniqueConstraint;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * One claimed idempotency key and, once the original finishes, its response.
 *
 * <p>Lives in the {@code platform} schema with the other cross-cutting infrastructure tables. It
 * belongs to no business module — every module's endpoints go through the same filter — so putting
 * it in one of their schemas would give that module ownership of something it does not own.</p>
 *
 * <p>Deliberately a plain {@code @Id} rather than {@link com.stockflow.common.persistence.BaseEntity}:
 * this is not a business aggregate, it has no version to guard (rows are written once and never
 * updated concurrently), and it has no audit columns because the row <i>is</i> the audit record.</p>
 *
 * <p><b>The unique constraint is the mechanism, not a safety net.</b> Two duplicate requests racing
 * on two threads both find no row and both try to insert; the database lets exactly one through.
 * Nothing in application code can substitute for that.</p>
 */
@Entity
@Table(
        name = "idempotency_record",
        schema = "platform",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_idempotency_key_caller", columnNames = {"idempotency_key", "caller"}),
        indexes = @Index(name = "ix_idempotency_expires", columnList = "expires_at"))
class IdempotencyRecordEntity implements Persistable<UUID> {
    /**
     * Application-assigned ids mean Spring Data cannot use "is the id null" to tell a new row from
     * an existing one, so {@code save()} would take the {@code merge()} path and issue a
     * {@code SELECT} before every {@code INSERT}. On every guarded request that is a wasted round trip on
     * a hot write path. See {@code BaseEntity} for the full reasoning — this entity does not extend
     * it (it needs neither the audit columns nor optimistic locking), so it carries its own flag.
     */
    @Transient
    private transient boolean persisted = false;

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

    @Column(name = "idempotency_key", nullable = false, length = 255, updatable = false)
    private String idempotencyKey;

    /** The user id, or {@code "anonymous"} - keys are namespaced so two clients cannot collide. */
    @Column(name = "caller", nullable = false, length = 64, updatable = false)
    private String caller;

    /** SHA-256 hex of method + path + body: 64 characters, fixed. */
    @Column(name = "request_fingerprint", nullable = false, length = 64, updatable = false)
    private String requestFingerprint;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private IdempotencyRecord.Status status;

    @Column(name = "response_status")
    private Integer responseStatus;

    /**
     * The original response body, replayed verbatim.
     *
     * <p>{@code TEXT} rather than a bounded {@code VARCHAR}: an API response has no natural length
     * limit, and truncating one would replay invalid JSON — a failure mode far worse than a large
     * row. The 24-hour retention is what keeps the table from growing without bound.</p>
     */
    @Column(name = "response_body", columnDefinition = "text")
    private String responseBody;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected IdempotencyRecordEntity() {
    }

    IdempotencyRecordEntity(UUID id, String idempotencyKey, String caller, String requestFingerprint,
                            Instant createdAt, Instant expiresAt) {
        this.id = id;
        this.idempotencyKey = idempotencyKey;
        this.caller = caller;
        this.requestFingerprint = requestFingerprint;
        this.status = IdempotencyRecord.Status.IN_PROGRESS;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    void complete(int responseStatus, String responseBody, Instant now) {
        this.status = IdempotencyRecord.Status.COMPLETED;
        this.responseStatus = responseStatus;
        this.responseBody = responseBody;
        this.completedAt = now;
    }

    IdempotencyRecord toRecord() {
        return new IdempotencyRecord(
                idempotencyKey,
                caller,
                requestFingerprint,
                status,
                responseStatus == null ? 0 : responseStatus,
                responseBody,
                createdAt,
                expiresAt);
    }

    boolean isExpiredAt(Instant now) {
        return !now.isBefore(expiresAt);
    }

    /**
     * How long an {@code IN_PROGRESS} claim is honoured before it is treated as dead.
     *
     * <h2>Why a claim needs its own, much shorter, lease</h2>
     *
     * <p>{@code expiresAt} is 24 hours out, which is right for a stored <i>response</i>: a client
     * retrying a day later should get the same answer rather than place a second order. It is
     * badly wrong for a claim. The claim row is committed in its own transaction before the request
     * runs, and it is removed in a {@code finally} block — which does not execute if the JVM dies.
     * OOM kill, {@code kill -9}, node eviction: the row survives with status {@code IN_PROGRESS},
     * and every retry of that key is answered 409 "still being processed" for the next 24 hours.
     * The client is told to retry and can never succeed. Exactly the lockout the store's contract
     * promises cannot happen.</p>
     *
     * <h2>Choosing the value</h2>
     *
     * <p>It must be comfortably longer than the slowest request this application can serve, because
     * a lease that expires while the original is genuinely still running lets a duplicate through
     * and the work happens twice — the one thing idempotency exists to prevent. It must also be
     * short enough that a crash does not stall a client for long. Five minutes clears both bars by
     * a wide margin: no synchronous endpoint here comes within an order of magnitude of it, and a
     * client retrying with backoff recovers within one cycle.</p>
     *
     * <p><b>If a long-running synchronous endpoint is ever added, raise this first.</b></p>
     */
    static final Duration CLAIM_TIMEOUT = Duration.ofMinutes(5);

    /**
     * True when this row is a claim whose owner is almost certainly gone.
     *
     * <p>Only {@code IN_PROGRESS} rows can be abandoned. A {@code COMPLETED} row is a stored
     * answer and stays for the full retention window regardless of age.</p>
     */
    boolean isAbandonedClaimAt(Instant now) {
        return status == IdempotencyRecord.Status.IN_PROGRESS
                && createdAt.plus(CLAIM_TIMEOUT).isBefore(now);
    }

    @Override
    public UUID getId() {
        return id;
    }
}
