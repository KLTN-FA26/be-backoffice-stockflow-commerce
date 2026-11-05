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
import com.stockflow.common.storage.PreparedFiles;
import com.stockflow.design.api.DesignArtifactRole;
import com.stockflow.design.internal.domain.DesignArtifact;
import com.stockflow.design.internal.domain.DesignArtifactRepository;
import com.stockflow.design.internal.domain.DesignDraft;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

/**
 * The files of a design draft: upload, list, download.
 *
 * <h2>The upload holds no lock while the file is scanned and stored</h2>
 *
 * <p>A design file is up to 50 MB; checking, scanning and writing it takes seconds. It used to happen
 * under the draft's {@code FOR UPDATE}, so every other writer of the draft - the reviewer, the
 * confirmation - waited that long, and a slow scanner held a pooled connection per upload. Now the
 * draft is read without a lock to fail fast, the file is prepared with no transaction at all
 * ({@link FileTransfers#prepare}), and a short transaction locks the draft, checks again what may have
 * changed meanwhile, and attaches the stored file - or leaves it to be deleted.</p>
 */
@Service
@Transactional
public class DesignArtifactService {
    private final DesignArtifactRepository designs;
    private final FileTransfers files;
    private final TransactionOperations tx;

    public DesignArtifactService(DesignArtifactRepository designs, FileTransfers files, TransactionOperations tx) {
        this.designs = designs;
        this.files = files;
        this.tx = tx;
    }

    @Auditable(action = AuditAction.UPDATE, resourceType = "design", resourceId = "#designId")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public DesignArtifact upload(UUID designId, UUID userId, FileUpload upload) {
        return upload(designId, userId, upload, null);
    }

    @Auditable(action = AuditAction.UPDATE, resourceType = "design", resourceId = "#designId")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public DesignArtifact upload(UUID designId, UUID userId, FileUpload upload, String requestKey) {
        return upload(designId, userId, DesignArtifactRole.CUSTOMER_PREVIEW, upload, requestKey);
    }

    /**
     * Not transactional itself; see the class javadoc. With an {@code Idempotency-Key}, a repeat of an
     * upload that already succeeded answers with the artifact it created, before anything is scanned
     * or stored; the same key with a different file is {@code IDEMPOTENCY_KEY_REUSED}.
     */
    @Auditable(action = AuditAction.UPDATE, resourceType = "design", resourceId = "#designId")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public DesignArtifact upload(UUID designId, UUID userId, DesignArtifactRole role,
                                 FileUpload upload, String requestKey) {
        String key = requestKey == null ? null
                : userId + ":" + role.name() + ":" + IdempotencyKeys.validate(requestKey);
        Optional<DesignArtifact> earlier = tx.execute(status -> {
            var draft = requireDraft(designId, userId, false);
            Optional<DesignArtifact> replay = artifactOf(designId, key);
            if (replay.isEmpty()) {
                requireChangeable(draft, designId);
            }
            return replay;
        });
        if (earlier.isPresent()) {
            try (var buffered = BufferedUpload.read(FileCategory.DESIGN_RENDER, upload)) {
                return replayed(earlier.get(), role, buffered.checksum(), upload);
            }
        }
        try (PreparedFiles prepared = files.prepare(FileCategory.DESIGN_RENDER, upload)) {
            PreparedFiles.PreparedFile file = prepared.single();
            return tx.execute(status -> {
                var draft = requireDraft(designId, userId, true);
                Optional<DesignArtifact> raced = artifactOf(designId, key);
                if (raced.isPresent()) {
                    // The same request committed while this one was scanning: answer with it, keep nothing of ours.
                    return replayed(raced.get(), role, file.sha256(), upload);
                }
                requireChangeable(draft, designId);
                var artifact = new DesignArtifact(Identifiers.newId(), designId, role, file.file(), file.sha256(), key);
                designs.attach(artifact);
                designs.recordEditor(designId, userId);
                prepared.adopt();
                return artifact;
            });
        }
    }

    @Transactional(readOnly = true)
    public List<DesignArtifact> list(UUID designId, UUID userId) {
        requireDraft(designId, userId, false);
        return designs.findArtifacts(designId);
    }

    /** A link that saves the file under its original name; audited, as the design leaves the system. */
    @Transactional(readOnly = true)
    @Auditable(action = AuditAction.EXPORT, resourceType = "design-artifact", resourceId = "#artifactId",
            details = "{designId: #designId}")
    public DownloadLink downloadLink(UUID designId, UUID artifactId, UUID userId) {
        var artifact = list(designId, userId).stream().filter(file -> file.id().equals(artifactId))
                .findFirst().orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        return files.downloadLink(artifact.file().key(), artifact.file().originalName());
    }

    private Optional<DesignArtifact> artifactOf(UUID designId, String key) {
        return key == null ? Optional.empty()
                : designs.findArtifacts(designId).stream().filter(a -> key.equals(a.uploadKey())).findFirst();
    }

    private void requireChangeable(DesignDraft draft, UUID designId) {
        draft.requireEditable();
        if (designs.hasSnapshot(designId)) {
            throw new BusinessException(ErrorCode.CONFLICT, "A confirmed design requires a new draft");
        }
    }

    private static DesignArtifact replayed(DesignArtifact artifact, DesignArtifactRole role, String checksum,
                                           FileUpload upload) {
        if (artifact.role() != role || !artifact.checksum().equals(checksum)
                || !artifact.file().originalName().equals(upload.originalName())
                || !upload.contentType().split(";", 2)[0].trim().equalsIgnoreCase(artifact.file().contentType())) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        return artifact;
    }

    private DesignDraft requireDraft(UUID designId, UUID userId, boolean forUpdate) {
        if (userId == null) { throw new BusinessException(ErrorCode.UNAUTHORIZED); }
        var draft = designs.findDraft(designId, userId, forUpdate)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        draft.requireAccess(userId);
        return draft;
    }

}
