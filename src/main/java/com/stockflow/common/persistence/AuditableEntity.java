package com.stockflow.common.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

/**
 * Base class carrying the four audit columns every business table needs.
 *
 * <p>BRD 3.19.3 asks for a "comprehensive audit log for all entities". Filling these by hand in
 * every service guarantees somebody forgets; Spring Data's auditing listener fills them from the
 * security context instead, so the answer to "who changed this row and when" is always there.</p>
 *
 * <p>The corresponding columns must exist in the migration:</p>
 * <pre>
 * created_at       TIMESTAMPTZ NOT NULL,
 * created_by       VARCHAR(100),
 * last_modified_at TIMESTAMPTZ,
 * last_modified_by VARCHAR(100)
 * </pre>
 */
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class AuditableEntity {

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @CreatedBy
    @Column(name = "created_by", length = 100, updatable = false)
    private String createdBy;

    @LastModifiedDate
    @Column(name = "last_modified_at")
    private Instant lastModifiedAt;

    @LastModifiedBy
    @Column(name = "last_modified_by", length = 100)
    private String lastModifiedBy;

    public Instant getCreatedAt() { return createdAt; }
    public String getCreatedBy() { return createdBy; }
    public Instant getLastModifiedAt() { return lastModifiedAt; }
    public String getLastModifiedBy() { return lastModifiedBy; }
}
