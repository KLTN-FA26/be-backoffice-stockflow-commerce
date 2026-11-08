package com.stockflow.design.internal.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/**
 * {@code design.design_document}: a design's layout as canonical JSON, and its SHA-256 (kltn-docs 12 BR-08, BR-10).
 * One row per design, keyed by the design; replaced whole on every save. The table checks the checksum against the
 * text itself ({@code ck_design_document_checksum}), so a row whose checksum lies cannot be written.
 *
 * <p>Not a {@code BaseEntity}: the row has no id or version of its own - the design's version is the lock a save
 * quotes. {@link Persistable} with an explicit new flag, because with an assigned key Spring Data would otherwise
 * merge - a SELECT before every INSERT.</p>
 */
@Entity
@Table(name = "design_document", schema = "design")
public class DesignDocumentJpaEntity implements Persistable<UUID> {

    @Id
    @Column(name = "draft_id", nullable = false, updatable = false)
    private UUID draftId;

    @Column(name = "document", nullable = false, columnDefinition = "text")
    private String document;

    @Column(name = "checksum", nullable = false, length = 64)
    private String checksum;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Transient
    private boolean fresh;

    protected DesignDocumentJpaEntity() {
    }

    public DesignDocumentJpaEntity(UUID draftId, String document, String checksum, Instant at) {
        this.draftId = draftId;
        this.document = document;
        this.checksum = checksum;
        this.updatedAt = at;
        this.fresh = true;
    }

    /** {@code canonical} replaces the layout; {@code sha256} must be its checksum. */
    public void replace(String canonical, String sha256, Instant at) {
        document = canonical;
        checksum = sha256;
        updatedAt = at;
    }

    @PostLoad
    @PostPersist
    void stored() {
        fresh = false;
    }

    @Override
    public UUID getId() { return draftId; }

    @Override
    public boolean isNew() { return fresh; }

    public UUID getDraftId() { return draftId; }
    public String getDocument() { return document; }
    public String getChecksum() { return checksum; }
    public Instant getUpdatedAt() { return updatedAt; }
}
