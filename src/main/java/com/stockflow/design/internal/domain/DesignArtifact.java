package com.stockflow.design.internal.domain;

import com.stockflow.common.storage.StoredFile;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import java.util.Objects;
import java.util.UUID;

/** Append-only file revision; confirmed snapshots reference an exact revision. */
public record DesignArtifact(UUID id, UUID designId, com.stockflow.design.api.DesignArtifactRole role,
                             StoredFile file, String checksum, String uploadKey) {
    public DesignArtifact(UUID id, UUID designId, StoredFile file, String checksum) {
        this(id, designId, com.stockflow.design.api.DesignArtifactRole.CUSTOMER_PREVIEW, file, checksum, null);
    }
    public DesignArtifact(UUID id, UUID designId, StoredFile file, String checksum, String uploadKey) {
        this(id, designId, com.stockflow.design.api.DesignArtifactRole.CUSTOMER_PREVIEW, file, checksum, uploadKey);
    }
    public DesignArtifact {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(designId, "designId");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(file, "file");
        if (file.sizeBytes() <= 0 || checksum == null || !checksum.matches("[0-9a-f]{64}")) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Invalid artifact size or checksum");
        }
    }
}
