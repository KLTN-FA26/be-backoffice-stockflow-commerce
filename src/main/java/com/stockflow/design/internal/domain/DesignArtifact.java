package com.stockflow.design.internal.domain;

import com.stockflow.common.storage.StoredFile;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import java.util.Objects;
import java.util.UUID;

/**
 * Append-only file revision; confirmed snapshots reference an exact revision. {@code documentChecksum} is the SHA-256 of
 * the canonical layout a customer's file was rendered from (kltn-docs 12 BR-10) and {@code templateVersion} the version
 * of the template it was drawn on; both null for a file of the staff workflow.
 */
public record DesignArtifact(UUID id, UUID designId, com.stockflow.design.api.DesignArtifactRole role,
                             StoredFile file, String checksum, String uploadKey, String documentChecksum,
                             Long templateVersion) {
    public DesignArtifact(UUID id, UUID designId, com.stockflow.design.api.DesignArtifactRole role, StoredFile file,
                          String checksum, String uploadKey) {
        this(id, designId, role, file, checksum, uploadKey, null, null);
    }
    public DesignArtifact(UUID id, UUID designId, StoredFile file, String checksum) {
        this(id, designId, com.stockflow.design.api.DesignArtifactRole.CUSTOMER_PREVIEW, file, checksum, null, null,
                null);
    }
    public DesignArtifact(UUID id, UUID designId, StoredFile file, String checksum, String uploadKey) {
        this(id, designId, com.stockflow.design.api.DesignArtifactRole.CUSTOMER_PREVIEW, file, checksum, uploadKey,
                null, null);
    }
    public DesignArtifact {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(designId, "designId");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(file, "file");
        if (file.sizeBytes() <= 0 || checksum == null || !checksum.matches("[0-9a-f]{64}")) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Invalid artifact size or checksum");
        }
        if (documentChecksum != null && !documentChecksum.matches("[0-9a-f]{64}")) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "documentChecksum must be a lower-case hex SHA-256");
        }
    }
}
