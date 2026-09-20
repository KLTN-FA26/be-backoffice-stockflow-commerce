package com.stockflow.design.internal.service;

import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.idempotency.IdempotencyKeys;
import com.stockflow.common.storage.BufferedUpload;
import com.stockflow.common.storage.DownloadLink;
import com.stockflow.common.storage.FileCategory;
import com.stockflow.common.storage.FileTransfers;
import com.stockflow.common.storage.FileUpload;
import com.stockflow.common.storage.StorageException;
import com.stockflow.common.storage.UploadInspection;
import com.stockflow.design.internal.domain.DesignArtifact;
import com.stockflow.design.internal.domain.DesignArtifactRepository;
import com.stockflow.design.internal.domain.DesignDraft;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
@Transactional
public class DesignArtifactService {
    private final DesignArtifactRepository designs;
    private final FileTransfers files;
    private final UploadInspection inspection;

    public DesignArtifactService(DesignArtifactRepository designs, FileTransfers files,
                                 UploadInspection inspection) {
        this.designs = designs;
        this.files = files;
        this.inspection = inspection;
    }

    @Auditable(action = AuditAction.UPDATE, resourceType = "design", resourceId = "#designId")
    public DesignArtifact upload(UUID designId, UUID userId, FileUpload upload) {
        return upload(designId, userId, upload, null);
    }

    @Auditable(action = AuditAction.UPDATE, resourceType = "design", resourceId = "#designId")
    public DesignArtifact upload(UUID designId, UUID userId, FileUpload upload, String requestKey) {
        return upload(designId, userId, com.stockflow.design.api.DesignArtifactRole.CUSTOMER_PREVIEW, upload, requestKey);
    }

    @Auditable(action = AuditAction.UPDATE, resourceType = "design", resourceId = "#designId")
    public DesignArtifact upload(UUID designId, UUID userId, com.stockflow.design.api.DesignArtifactRole role,
                                 FileUpload upload, String requestKey) {
        var draft = requireDraft(designId, userId, true);
        String key = requestKey == null ? null
                : userId + ":" + role.name() + ":" + IdempotencyKeys.validate(requestKey);
        try (var buffered = BufferedUpload.read(FileCategory.DESIGN_RENDER, upload)) {
            if (key != null) {
                var existing = designs.findArtifacts(designId).stream().filter(a -> key.equals(a.uploadKey())).findFirst();
                if (existing.isPresent()) {
                    var artifact = existing.get();
                    if (artifact.role() != role || !artifact.checksum().equals(buffered.checksum())
                            || !artifact.file().originalName().equals(upload.originalName())
                            || !upload.contentType().split(";", 2)[0].trim().equalsIgnoreCase(artifact.file().contentType())) {
                        throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
                    }
                    return artifact;
                }
            }
            draft.requireEditable();
            if (designs.hasSnapshot(designId)) {
                throw new BusinessException(ErrorCode.CONFLICT, "A confirmed design requires a new draft");
            }
            try (var content = buffered.open()) { inspection.requireClean(content); }
            try (var content = new BufferedInputStream(buffered.open())) {
                var file = files.store(FileCategory.DESIGN_RENDER,
                        new FileUpload(upload.originalName(), upload.contentType(), upload.sizeBytes(), content));
                var artifact = new DesignArtifact(Identifiers.newId(), designId, role, file, buffered.checksum(), key);
                designs.attach(artifact);
                designs.recordEditor(designId, userId);
                return artifact;
            }
        } catch (IOException ex) {
            throw new StorageException("Could not read buffered artifact", ex);
        }
    }

    @Transactional(readOnly = true)
    public List<DesignArtifact> list(UUID designId, UUID userId) {
        requireDraft(designId, userId, false);
        return designs.findArtifacts(designId);
    }

    @Transactional(readOnly = true)
    public DownloadLink downloadLink(UUID designId, UUID artifactId, UUID userId) {
        var artifact = list(designId, userId).stream().filter(file -> file.id().equals(artifactId))
                .findFirst().orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        return files.downloadLink(artifact.file().key());
    }

    private DesignDraft requireDraft(UUID designId, UUID userId, boolean forUpdate) {
        if (userId == null) { throw new BusinessException(ErrorCode.UNAUTHORIZED); }
        var draft = designs.findDraft(designId, userId, forUpdate)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        draft.requireAccess(userId);
        return draft;
    }

}
