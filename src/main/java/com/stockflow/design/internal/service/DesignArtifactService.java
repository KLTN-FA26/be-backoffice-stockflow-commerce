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
import com.stockflow.common.storage.StagedUploads;
import com.stockflow.common.storage.UploadSlot;
import com.stockflow.common.storage.UploadSlotRequest;
import com.stockflow.design.api.DesignArtifactRole;
import com.stockflow.design.internal.domain.DesignReadRights;
import com.stockflow.design.internal.domain.DesignArtifact;
import com.stockflow.design.internal.domain.DesignArtifactRepository;
import com.stockflow.design.internal.domain.DesignDraft;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

/**
 * The files of a design draft: upload, list, download.
 *
 * <h2>Two ways in, one way to attach</h2>
 *
 * <p>The browser either sends the file through the backend ({@link #upload}, multipart), or writes it straight to the
 * object store and names it ({@link #beginUpload}, then {@link #attachUpload}) - the way to prefer: a 50 MB design no
 * longer ties up a request for the whole of a customer's upload. Either way the file is checked, scanned and stored
 * with no transaction and no lock held, and a short transaction locks the draft, checks again what may have changed
 * meanwhile, and attaches it - or leaves it to be deleted.</p>
 *
 * <p>A design file is up to 50 MB; checking, scanning and writing it takes seconds. It used to happen under the
 * draft's {@code FOR UPDATE}, so every other writer of the draft - the reviewer, the confirmation - waited that long,
 * and a slow scanner held a pooled connection per upload.</p>
 */
@Service
@Transactional
public class DesignArtifactService {
    private final DesignArtifactRepository designs;
    private final FileTransfers files;
    private final StagedUploads staged;
    private final TransactionOperations tx;
    private final DesignPreflight designPreflight;
    private final com.stockflow.product.api.PrintConfigs printConfigs;

    public DesignArtifactService(DesignArtifactRepository designs, FileTransfers files, StagedUploads staged,
                                 TransactionOperations tx, DesignPreflight designPreflight,
                                 com.stockflow.product.api.PrintConfigs printConfigs) {
        this.designPreflight = designPreflight;
        this.printConfigs = printConfigs;
        this.designs = designs;
        this.files = files;
        this.staged = staged;
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
        return upload(designId, userId, role, upload, requestKey, null);
    }

    /**
     * {@link #upload(UUID, UUID, DesignArtifactRole, FileUpload, String)} of a file rendered from a customer's layout:
     * {@code documentChecksum} is the SHA-256 of the canonical layout it was rendered from, which a submission checks
     * against the layout as it is then (kltn-docs 12 BR-10). A repeat with the same key must name the same layout.
     */
    @Auditable(action = AuditAction.UPDATE, resourceType = "design", resourceId = "#designId")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public DesignArtifact upload(UUID designId, UUID userId, DesignArtifactRole role,
                                 FileUpload upload, String requestKey, String documentChecksum) {
        requireLayoutChecksum(documentChecksum);
        // ":key:" and ":upload:" keep a client's key from ever naming a direct upload's.
        String key = requestKey == null ? null
                : userId + ":" + role.name() + ":key:" + IdempotencyKeys.validate(requestKey);
        Optional<DesignArtifact> earlier = changeableOrReplay(designId, userId, key);
        if (earlier.isPresent()) {
            try (var buffered = BufferedUpload.read(FileCategory.DESIGN_RENDER, upload)) {
                return replayed(earlier.get(), role, buffered.checksum(), upload, documentChecksum);
            }
        }
        try (PreparedFiles prepared = files.prepare(FileCategory.DESIGN_RENDER, upload)) {
            return attach(designId, userId, role, key, documentChecksum, prepared,
                    raced -> replayed(raced, role, prepared.single().sha256(), upload, documentChecksum));
        }
    }

    /**
     * A slot to upload a file of this draft straight to the object store ({@link StagedUploads}): only for a draft
     * the caller may change and that still takes files, so nobody uploads for a draft they cannot attach to.
     */
    @Transactional(readOnly = true)
    public UploadSlot beginUpload(UUID designId, UUID userId, UploadSlotRequest request) {
        var draft = requireDraft(designId, userId, false);
        draft.requireEditor(userId);
        requireChangeable(draft, designId);
        return staged.begin(FileCategory.DESIGN_RENDER, userId, request);
    }

    /**
     * Attaches a file the caller uploaded through {@link #beginUpload}. Not transactional itself: the upload is
     * checked, scanned and moved into place first ({@link StagedUploads#accept}). Attaching the same upload again
     * answers with the artifact it made.
     */
    @Auditable(action = AuditAction.UPDATE, resourceType = "design", resourceId = "#designId")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public DesignArtifact attachUpload(UUID designId, UUID userId, DesignArtifactRole role, UUID uploadId) {
        return attachUpload(designId, userId, role, uploadId, null);
    }

    /** {@link #attachUpload(UUID, UUID, DesignArtifactRole, UUID)} of a file rendered from a customer's layout. */
    @Auditable(action = AuditAction.UPDATE, resourceType = "design", resourceId = "#designId")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public DesignArtifact attachUpload(UUID designId, UUID userId, DesignArtifactRole role, UUID uploadId,
                                       String documentChecksum) {
        requireLayoutChecksum(documentChecksum);
        if (uploadId == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "uploadId is required");
        }
        String key = userId + ":" + role.name() + ":upload:" + uploadId;
        Optional<DesignArtifact> earlier = changeableOrReplay(designId, userId, key);
        if (earlier.isPresent()) {
            // The same upload again names the same layout, as a multipart repeat must (review of 2F-5).
            if (!java.util.Objects.equals(earlier.get().documentChecksum(), documentChecksum)) {
                throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
            }
            return earlier.get();
        }
        try (PreparedFiles prepared = staged.accept(FileCategory.DESIGN_RENDER, userId, List.of(uploadId))) {
            return attach(designId, userId, role, key, documentChecksum, prepared, UnaryOperator.identity());
        }
    }

    /**
     * BR-DSG-001's machine checks of one revision, for the reviewer and the designer (see {@link DesignPreflight}). Not
     * transactional itself: the artifact is resolved through its draft in a short read, and the file - up to 50 MB - is
     * read with no connection held.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public DesignPreflight.Report preflight(UUID designId, UUID artifactId, UUID userId) {
        return preflight(designId, artifactId, userId, DesignReadRights.NONE);
    }

    /** {@link #preflight(UUID, UUID, UUID)} for a reader who may read others' designs too (plan D7). */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public DesignPreflight.Report preflight(UUID designId, UUID artifactId, UUID userId, DesignReadRights rights) {
        var file = tx.execute(status -> list(designId, userId, rights).stream()
                .filter(found -> found.id().equals(artifactId))
                .findFirst().orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND)).file());
        return designPreflight.inspect(file.key(), file.contentType());
    }

    @Transactional(readOnly = true)
    public List<DesignArtifact> list(UUID designId, UUID userId) {
        return list(designId, userId, DesignReadRights.NONE);
    }

    /** The design's files, to whoever reads the design (BR-03, plan D7). */
    @Transactional(readOnly = true)
    public List<DesignArtifact> list(UUID designId, UUID userId, DesignReadRights rights) {
        requireDraft(designId, userId, false, rights);
        return designs.findArtifacts(designId);
    }

    /** A link that saves the file under its original name; audited, as the design leaves the system. */
    @Transactional(readOnly = true)
    @Auditable(action = AuditAction.EXPORT, resourceType = "design-artifact", resourceId = "#artifactId",
            details = "{designId: #designId}")
    public DownloadLink downloadLink(UUID designId, UUID artifactId, UUID userId) {
        return downloadLink(designId, artifactId, userId, DesignReadRights.NONE);
    }

    /** {@link #downloadLink(UUID, UUID, UUID)} for a reader who may read others' designs too (plan D7). */
    @Transactional(readOnly = true)
    @Auditable(action = AuditAction.EXPORT, resourceType = "design-artifact", resourceId = "#artifactId",
            details = "{designId: #designId}")
    public DownloadLink downloadLink(UUID designId, UUID artifactId, UUID userId, DesignReadRights rights) {
        var artifact = list(designId, userId, rights).stream().filter(file -> file.id().equals(artifactId))
                .findFirst().orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        return files.downloadLink(artifact.file().key(), artifact.file().originalName());
    }

    /** Without a lock: the artifact this key made already, or - when none - a draft that still takes files. */
    private Optional<DesignArtifact> changeableOrReplay(UUID designId, UUID userId, String key) {
        return tx.execute(status -> {
            var draft = requireDraft(designId, userId, false);
            draft.requireEditor(userId);
            Optional<DesignArtifact> replay = artifactOf(designId, key);
            if (replay.isEmpty()) {
                requireChangeable(draft, designId);
            }
            return replay;
        });
    }

    /** The short transaction: lock, check again, attach - or answer with what the same request made meanwhile. */
    private DesignArtifact attach(UUID designId, UUID userId, DesignArtifactRole role, String key,
                                  String documentChecksum, PreparedFiles prepared,
                                  UnaryOperator<DesignArtifact> onReplay) {
        PreparedFiles.PreparedFile file = prepared.single();
        return tx.execute(status -> {
            var draft = requireDraft(designId, userId, true);
            Optional<DesignArtifact> raced = artifactOf(designId, key);
            if (raced.isPresent()) {
                // The same request committed while this one was scanning: answer with it and keep nothing of ours -
                // unless it is the very file the committed row names (a direct upload's key is the upload's own).
                if (raced.get().file().key().equals(file.file().key())) {
                    prepared.adopt();
                }
                return onReplay.apply(raced.get());
            }
            requireChangeable(draft, designId);
            var artifact = new DesignArtifact(Identifiers.newId(), designId, role, file.file(), file.sha256(), key,
                    documentChecksum, renderedOnTemplate(draft, documentChecksum));
            designs.attach(artifact);
            prepared.adopt();
            return artifact;
        });
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

    /**
     * The version of the template a file rendered from a layout was drawn on, as it is when the file is attached: a
     * template is edited in place and a draft follows it (B39), so a submission needs files drawn on it as it is then.
     * The window between the studio fetching the template and the file arriving is the one this cannot see.
     */
    private Long renderedOnTemplate(DesignDraft draft, String documentChecksum) {
        if (documentChecksum == null) {
            return null;
        }
        if (draft.templateId() == null) {
            throw new BusinessException(ErrorCode.CONFLICT, "Only a design drawn on a template takes rendered files");
        }
        return printConfigs.byId(draft.templateId()).map(com.stockflow.product.api.PrintConfig::version)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRINT_CONFIG_NOT_FOUND));
    }

    /** Refused before anything is read: a layout checksum is a lower-case hex SHA-256, or absent. */
    private static void requireLayoutChecksum(String documentChecksum) {
        if (documentChecksum != null && !documentChecksum.matches("[0-9a-f]{64}")) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "documentChecksum must be a lower-case hex SHA-256");
        }
    }

    private static DesignArtifact replayed(DesignArtifact artifact, DesignArtifactRole role, String checksum,
                                           FileUpload upload, String documentChecksum) {
        if (!java.util.Objects.equals(artifact.documentChecksum(), documentChecksum)) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        if (artifact.role() != role || !artifact.checksum().equals(checksum)
                || !artifact.file().originalName().equals(upload.originalName())
                || !upload.contentType().split(";", 2)[0].trim().equalsIgnoreCase(artifact.file().contentType())) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        return artifact;
    }

    private DesignDraft requireDraft(UUID designId, UUID userId, boolean forUpdate) {
        return requireDraft(designId, userId, forUpdate, DesignReadRights.NONE);
    }

    private DesignDraft requireDraft(UUID designId, UUID userId, boolean forUpdate, DesignReadRights rights) {
        if (userId == null) { throw new BusinessException(ErrorCode.UNAUTHORIZED); }
        var draft = designs.findDraft(designId, forUpdate)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        draft.requireAccess(userId, rights, () -> designs.quoteRequestOpen(designId));
        return draft;
    }

}
