package com.stockflow.design.internal.service;

import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.idempotency.IdempotencyKeys;
import com.stockflow.common.storage.BufferedUpload;
import com.stockflow.common.storage.ContentTypePolicy;
import com.stockflow.common.storage.DownloadLink;
import com.stockflow.common.storage.FileCategory;
import com.stockflow.common.storage.FileTransfers;
import com.stockflow.common.storage.FileUpload;
import com.stockflow.common.storage.PreparedFiles;
import com.stockflow.common.storage.StagedUploads;
import com.stockflow.common.storage.UploadSlot;
import com.stockflow.common.storage.UploadSlotRequest;
import com.stockflow.design.internal.domain.CustomerDesignRules;
import com.stockflow.design.internal.entity.DesignAssetJpaEntity;
import com.stockflow.design.internal.entity.DesignDraftJpaEntity;
import com.stockflow.design.internal.repository.DesignAssetJpaRepository;
import com.stockflow.design.internal.repository.DesignDraftJpaRepository;
import com.stockflow.product.api.PrintConfig;
import com.stockflow.product.api.PrintConfigs;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

/**
 * The images a customer uploads into their design (kltn-docs 12 §4.2 step 5, BR-04): checked against the design's
 * template - its file types and size limit, as the template is now (B39) - scanned, measured upright and stored; only a
 * draft takes them (BR-07, BR-08); at most {@value #MAX_ASSETS} a design.
 *
 * <p>Two ways in, as for a design's files: through the backend ({@link #upload}, multipart) or straight to the object
 * store ({@link #beginUpload}, then {@link #attachUpload}). Either way the file is checked, scanned, measured and stored
 * with no transaction and no lock held, and a short transaction locks the design, checks again what may have changed
 * meanwhile - its status, the image count, the template - and records the image. An upload does not move the design's
 * version: the layout that places the image is the change, and it is saved with the version (BR-11).</p>
 *
 * <p>Only the design's owner reaches its images; anyone else, and everyone once it is archived, is told it does not
 * exist (BR-03, B38).</p>
 */
@Service
@Transactional
public class DesignAssetService {

    /** Images a design holds (plan 2F-4): more than any cup or bag layout uses, few enough to list in one page. */
    static final int MAX_ASSETS = 100;

    private static final long MEBIBYTE = 1024L * 1024L;

    private final DesignDraftJpaRepository drafts;
    private final DesignAssetJpaRepository assets;
    private final PrintConfigs printConfigs;
    private final FileTransfers files;
    private final StagedUploads staged;
    private final TransactionOperations tx;
    private final EntityManager entityManager;
    private final Clock clock;

    public DesignAssetService(DesignDraftJpaRepository drafts, DesignAssetJpaRepository assets,
                              PrintConfigs printConfigs, FileTransfers files, StagedUploads staged,
                              TransactionOperations tx, EntityManager entityManager, Clock clock) {
        this.drafts = drafts;
        this.assets = assets;
        this.printConfigs = printConfigs;
        this.files = files;
        this.staged = staged;
        this.tx = tx;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    /** An image of a design, as the studio reads it: its size in pixels is the server's measurement, upright. */
    public record AssetView(UUID assetId, UUID designId, String originalName, String contentType, long sizeBytes,
                            String checksum, int widthPx, int heightPx, Instant storedAt) {
    }

    /**
     * What a design takes, read before an upload is scanned: the image an earlier attempt with the same key already
     * made, or the template's file types and size limit.
     */
    private record Admission(DesignAssetJpaEntity replay, Set<String> types, long maxBytes) {

        void requireAccepts(String contentType, long sizeBytes) {
            String type = ContentTypePolicy.normalise(contentType);
            if (!types.contains(type)) {
                throw new BusinessException(ErrorCode.UNSUPPORTED_MEDIA_TYPE,
                        "This design's template takes " + String.join(", ", types.stream().sorted().toList()));
            }
            if (sizeBytes > maxBytes) {
                throw new BusinessException(ErrorCode.PAYLOAD_TOO_LARGE,
                        "This design's template takes images of at most " + maxBytes / MEBIBYTE + " MB");
            }
        }
    }

    /**
     * Not transactional itself; see the class javadoc. With an {@code Idempotency-Key}, a repeat of an upload that
     * already succeeded answers with the image it made, before anything is scanned or stored; the same key with a
     * different file is {@code IDEMPOTENCY_KEY_REUSED}.
     */
    @Auditable(action = AuditAction.UPDATE, resourceType = "design", resourceId = "#designId")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AssetView upload(UUID designId, UUID userId, FileUpload upload, String requestKey) {
        // ":key:" and ":upload:" keep a client's key from ever naming a direct upload's.
        String key = requestKey == null ? null : userId + ":asset:key:" + IdempotencyKeys.validate(requestKey);
        Admission admission = tx.execute(status -> admit(designId, userId, key));
        if (admission.replay() != null) {
            try (var buffered = BufferedUpload.read(FileCategory.DESIGN_ASSET, upload)) {
                return replayed(admission.replay(), buffered.checksum(), upload);
            }
        }
        admission.requireAccepts(upload.contentType(), upload.sizeBytes());
        try (PreparedFiles prepared = files.prepare(FileCategory.DESIGN_ASSET, upload)) {
            return attach(designId, userId, key, prepared,
                    raced -> replayed(raced, prepared.single().sha256(), upload));
        }
    }

    /**
     * A slot to upload an image of this design straight to the object store: only for a draft of the caller's that
     * still has room, and only for a type and size its template takes, so nobody uploads what cannot be attached.
     */
    @Transactional(readOnly = true)
    public UploadSlot beginUpload(UUID designId, UUID userId, UploadSlotRequest request) {
        Admission admission = admit(designId, userId, null);
        admission.requireAccepts(request.contentType(), request.sizeBytes());
        return staged.begin(FileCategory.DESIGN_ASSET, userId, request);
    }

    /**
     * Records an image the caller uploaded through {@link #beginUpload}. Not transactional itself: the upload is
     * checked, scanned, measured and moved into place first ({@link StagedUploads#accept}). Attaching the same upload
     * again answers with the image it made.
     */
    @Auditable(action = AuditAction.UPDATE, resourceType = "design", resourceId = "#designId")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AssetView attachUpload(UUID designId, UUID userId, UUID uploadId) {
        if (uploadId == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "uploadId is required");
        }
        String key = userId + ":asset:upload:" + uploadId;
        Admission admission = tx.execute(status -> admit(designId, userId, key));
        if (admission.replay() != null) {
            return view(admission.replay());
        }
        try (PreparedFiles prepared = staged.accept(FileCategory.DESIGN_ASSET, userId, List.of(uploadId))) {
            return attach(designId, userId, key, prepared, UnaryOperator.identity());
        }
    }

    /** The design's images, newest first; to its owner, and to a reviewer while it is pending (plan D7). */
    @Transactional(readOnly = true)
    public List<AssetView> list(UUID designId, UUID userId, boolean reviewer) {
        readable(designId, userId, reviewer);
        return assets.findByDraftIdOrderByCreatedAtDescIdDesc(designId).stream()
                .map(DesignAssetService::view).toList();
    }

    /**
     * A link the studio draws the image from, shown inline. Not audited: it is the customer's own upload, opened in
     * their own studio, every time a layout is drawn.
     */
    @Transactional(readOnly = true)
    public DownloadLink link(UUID designId, UUID assetId, UUID userId, boolean reviewer) {
        readable(designId, userId, reviewer);
        DesignAssetJpaEntity asset = assets.findByDraftIdAndId(designId, assetId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        return files.viewLink(asset.getStorageKey());
    }

    /** Without a lock: the image this key made already, or - when none - what a draft with room takes. */
    private Admission admit(UUID designId, UUID userId, String key) {
        DesignDraftJpaEntity draft = ownDesign(designId, userId, false);
        Optional<DesignAssetJpaEntity> replay = key == null ? Optional.empty()
                : assets.findByDraftIdAndUploadKey(designId, key);
        if (replay.isPresent()) {
            return new Admission(replay.get(), Set.of(), 0);
        }
        CustomerDesignRules.requireDraft(draft.getStatus());
        requireRoom(designId);
        PrintConfig template = templateOf(draft);
        return new Admission(null, Set.copyOf(template.allowedFileFormats()), template.maxFileSizeMb() * MEBIBYTE);
    }

    /**
     * The short transaction: lock the design, check again, record the image - or answer with what the same request
     * made meanwhile. The checks are repeated because the scan took time: the design may have been submitted, other
     * uploads may have filled it, the Sales Manager may have narrowed the template.
     */
    private AssetView attach(UUID designId, UUID userId, String key, PreparedFiles prepared,
                             UnaryOperator<AssetView> onReplay) {
        PreparedFiles.PreparedFile file = prepared.single();
        if (file.dimensions() == null) {
            throw new IllegalStateException("A design image is measured when it is prepared");
        }
        return tx.execute(status -> {
            DesignDraftJpaEntity draft = ownDesign(designId, userId, true);
            Optional<DesignAssetJpaEntity> raced = key == null ? Optional.empty()
                    : assets.findByDraftIdAndUploadKey(designId, key);
            if (raced.isPresent()) {
                // The same request committed while this one was scanning: answer with it and keep nothing of ours -
                // unless the file is the one the committed row names (a direct upload's key is the upload's own, and
                // a retry after an expired claim prepared it again), which closing would delete from under it.
                if (raced.get().getStorageKey().equals(file.file().key())) {
                    prepared.adopt();
                }
                return onReplay.apply(view(raced.get()));
            }
            CustomerDesignRules.requireDraft(draft.getStatus());
            requireRoom(designId);
            PrintConfig template = templateOf(draft);
            String type = ContentTypePolicy.normalise(file.file().contentType());
            new Admission(null, Set.copyOf(template.allowedFileFormats()), template.maxFileSizeMb() * MEBIBYTE)
                    .requireAccepts(type, file.file().sizeBytes());
            var asset = new DesignAssetJpaEntity(designId, Identifiers.newId(), file.file().key(),
                    file.file().originalName(), type, file.file().sizeBytes(), file.sha256(),
                    file.dimensions().width(), file.dimensions().height(), key, clock.instant(), userId.toString());
            assets.saveAndFlush(asset);
            prepared.adopt();
            return view(asset);
        });
    }

    /**
     * The caller's own design, drawn on a template. {@code lock} takes the design's row lock, which serialises the
     * image count with every other write of the design, and reads the row as it is now.
     */
    private DesignDraftJpaEntity ownDesign(UUID designId, UUID userId, boolean lock) {
        if (userId == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        DesignDraftJpaEntity draft = (lock ? drafts.lockById(designId) : drafts.findById(designId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (lock) {
            entityManager.refresh(draft);
        }
        CustomerDesignRules.requireOwner(userId, draft.getOwnerUserId(), draft.getStatus());
        if (draft.getTemplateId() == null) {
            throw new BusinessException(ErrorCode.CONFLICT, "This design was not started on a template");
        }
        return draft;
    }

    private void readable(UUID designId, UUID userId, boolean reviewer) {
        DesignDraftJpaEntity draft = drafts.findById(designId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        CustomerDesignRules.requireReader(userId, draft.getOwnerUserId(), draft.getStatus(),
                draft.getTemplateId() != null, reviewer);
    }

    private void requireRoom(UUID designId) {
        if (assets.countByDraftId(designId) >= MAX_ASSETS) {
            throw new BusinessException(ErrorCode.DESIGN_ASSET_LIMIT_REACHED,
                    "A design holds at most " + MAX_ASSETS + " images");
        }
    }

    /** The template as it is now, active or not (B39). */
    private PrintConfig templateOf(DesignDraftJpaEntity draft) {
        return printConfigs.byId(draft.getTemplateId())
                .orElseThrow(() -> new BusinessException(ErrorCode.PRINT_CONFIG_NOT_FOUND));
    }

    private static AssetView replayed(DesignAssetJpaEntity earlier, String checksum, FileUpload upload) {
        return replayed(view(earlier), checksum, upload);
    }

    /** The same key names the same upload: same bytes, name and type, or the key was reused for another file. */
    private static AssetView replayed(AssetView earlier, String checksum, FileUpload upload) {
        if (!earlier.checksum().equals(checksum) || !earlier.originalName().equals(upload.originalName())
                || !ContentTypePolicy.normalise(upload.contentType()).equals(earlier.contentType())) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        return earlier;
    }

    private static AssetView view(DesignAssetJpaEntity asset) {
        return new AssetView(asset.getAssetId(), asset.getDraftId(), asset.getOriginalName(), asset.getContentType(),
                asset.getSizeBytes(), asset.getChecksum(), asset.getWidthPx(), asset.getHeightPx(),
                asset.getCreatedAt());
    }
}
