package com.stockflow.design.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import com.stockflow.common.storage.StoredFile;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import com.stockflow.design.api.DesignArtifactRole;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

/** No mutation methods: replacement inserts another revision. */
@Entity
@Table(name = "design_artifact", schema = "design")
public class DesignArtifactJpaEntity extends BaseEntity {
    @Column(name = "draft_id", nullable = false, updatable = false)
    private UUID draftId;
    @Column(name = "storage_key", nullable = false, updatable = false, length = 500)
    private String storageKey;
    @Column(name = "original_name", nullable = false, updatable = false, length = 255)
    private String originalName;
    @Column(name = "content_type", nullable = false, updatable = false, length = 100)
    private String contentType;
    @Column(name = "size_bytes", nullable = false, updatable = false)
    private long sizeBytes;
    @Column(name = "stored_at", nullable = false, updatable = false)
    private Instant storedAt;
    @Column(name = "checksum", nullable = false, updatable = false, length = 64)
    private String checksum;
    @Enumerated(EnumType.STRING)
    @Column(name = "artifact_role", nullable = false, updatable = false, length = 40)
    private DesignArtifactRole role = DesignArtifactRole.CUSTOMER_PREVIEW;

    @Column(name = "upload_key", length = 400, updatable = false)
    private String uploadKey;
    public String getUploadKey() { return uploadKey; }
    public void setUploadKey(String value) { uploadKey = value; }

    protected DesignArtifactJpaEntity() { }

    public DesignArtifactJpaEntity(UUID id, UUID draftId, StoredFile file, String checksum) {
        this(id, draftId, DesignArtifactRole.CUSTOMER_PREVIEW, file, checksum);
    }

    public DesignArtifactJpaEntity(UUID id, UUID draftId, DesignArtifactRole role, StoredFile file, String checksum) {
        super(id);
        this.draftId = draftId;
        this.role = role;
        this.storageKey = file.key();
        this.originalName = file.originalName();
        this.contentType = file.contentType();
        this.sizeBytes = file.sizeBytes();
        this.storedAt = file.storedAt();
        this.checksum = checksum;
    }

    public UUID getDraftId() { return draftId; }
    public DesignArtifactRole getRole() { return role; }
    public String getChecksum() { return checksum; }
    public StoredFile storedFile() {
        return new StoredFile(storageKey, originalName, contentType, sizeBytes, storedAt);
    }
}
