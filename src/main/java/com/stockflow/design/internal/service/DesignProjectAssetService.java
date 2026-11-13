package com.stockflow.design.internal.service;

import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.idempotency.IdempotencyKeys;
import com.stockflow.common.storage.BufferedUpload;
import com.stockflow.common.storage.ContentTypePolicy;
import com.stockflow.common.storage.DownloadLink;
import com.stockflow.common.storage.FileCategory;
import com.stockflow.common.storage.FileTransfers;
import com.stockflow.common.storage.FileUpload;
import com.stockflow.common.storage.PreparedFiles;
import com.stockflow.design.internal.domain.CustomerDesignRules;
import com.stockflow.design.internal.domain.DesignProjectRules;
import com.stockflow.design.internal.domain.ProjectEventType;
import com.stockflow.design.internal.entity.DesignAssetJpaEntity;
import com.stockflow.design.internal.entity.DesignDraftJpaEntity;
import com.stockflow.design.internal.entity.DesignProjectAssetJpaEntity;
import com.stockflow.design.internal.entity.DesignProjectJpaEntity;
import com.stockflow.design.internal.repository.DesignAssetJpaRepository;
import com.stockflow.design.internal.repository.DesignDraftJpaRepository;
import com.stockflow.design.internal.repository.DesignProjectAssetJpaRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

/**
 * A design project's library (kltn-docs 12 §4.9 step 29; BR-18; B43): logos and pictures uploaded once - scanned,
 * checked and measured as a design's images are ({@link FileCategory#DESIGN_ASSET}) - at most
 * {@value DesignProjectRules#MAX_ASSETS} a project, and placed on any design of the project as a copy
 * ({@link DesignAssetService#placeFromLibrary}), checked again against that design's template. Removing a file from the
 * library leaves every design that used it as it is; the file itself is swept once nothing names it.
 *
 * <p>The library takes the image types a design takes (PNG, JPEG, WebP). B43 assumes SVG and PDF as well, but no
 * template takes them, so a file of either could never be placed: they are refused at the door instead.</p>
 *
 * <p>A file comes in two ways: uploaded, retried safely with an {@code Idempotency-Key} (FE request 2026-11-13 E-5);
 * or saved from an image of a design of the project ({@link #saveFromDesign}, E-2, beyond kltn-docs), the reverse of
 * placing one - a server-side copy. Either way a repeat answers with the file the first request made and takes no
 * second place in the library: the row carries what the request is named by ({@code upload_key}).</p>
 */
@Service
@Transactional
public class DesignProjectAssetService {

    /** As {@code ck_design_project_asset_type}: the image types the library takes. */
    private static final Set<String> LIBRARY_TYPES = Set.of("image/png", "image/jpeg", "image/webp");

    private final DesignProjectService projects;
    private final DesignProjectAssetJpaRepository assets;
    private final DesignDraftJpaRepository drafts;
    private final DesignAssetJpaRepository designAssets;
    private final FileTransfers files;
    private final TransactionOperations tx;

    public DesignProjectAssetService(DesignProjectService projects, DesignProjectAssetJpaRepository assets,
                                     DesignDraftJpaRepository drafts, DesignAssetJpaRepository designAssets,
                                     FileTransfers files, TransactionOperations tx) {
        this.projects = projects;
        this.assets = assets;
        this.drafts = drafts;
        this.designAssets = designAssets;
        this.files = files;
        this.tx = tx;
    }

    /** A library file as the studio lists it. */
    public record LibraryAssetView(UUID assetId, UUID projectId, String originalName, String contentType,
                                   long sizeBytes, String checksum, int widthPx, int heightPx, Instant storedAt) {
    }

    /**
     * Uploads a file into the library. Not transactional itself: the file is checked, scanned and stored with no lock
     * held, and a short transaction locks the project, checks again that it is open with room, and records the file.
     *
     * <p>With an {@code Idempotency-Key}, a repeat of an upload that already succeeded answers with the file it made
     * before anything is scanned or stored; the same key with a different file is {@code IDEMPOTENCY_KEY_REUSED}. The
     * key is bound to the caller and looked up in this project only, as a design image's is
     * ({@link DesignAssetService#upload}).</p>
     */
    @Auditable(action = AuditAction.UPDATE, resourceType = "design-project", resourceId = "#projectId")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public LibraryAssetView upload(UUID projectId, UUID actor, FileUpload upload, String requestKey) {
        // ":key:" and ":design:" keep a client's key from ever naming a file saved from a design.
        String key = requestKey == null ? null : actor + ":library:key:" + IdempotencyKeys.validate(requestKey);
        Optional<DesignProjectAssetJpaEntity> earlier = tx.execute(status -> {
            DesignProjectJpaEntity project = projects.lock(projectId, actor);
            Optional<DesignProjectAssetJpaEntity> replay = key == null ? Optional.empty()
                    : assets.findByProjectIdAndUploadKey(projectId, key);
            if (replay.isEmpty()) {
                DesignProjectRules.requireActive(project.getStatus());
                requireRoom(project);
            }
            return replay;
        });
        if (earlier.isPresent()) {
            try (var buffered = BufferedUpload.read(FileCategory.DESIGN_ASSET, upload)) {
                return replayed(view(earlier.get()), buffered.checksum(), upload);
            }
        }
        try (PreparedFiles prepared = files.prepare(FileCategory.DESIGN_ASSET, upload)) {
            PreparedFiles.PreparedFile file = prepared.single();
            if (file.dimensions() == null) {
                throw new IllegalStateException("A design image is measured when it is prepared");
            }
            return tx.execute(status -> {
                DesignProjectJpaEntity project = projects.lock(projectId, actor);
                Optional<DesignProjectAssetJpaEntity> raced = key == null ? Optional.empty()
                        : assets.findByProjectIdAndUploadKey(projectId, key);
                if (raced.isPresent()) {
                    // The same request committed while this one was scanning: answer with it and keep nothing of ours.
                    return replayed(view(raced.get()), file.sha256(), upload);
                }
                DesignProjectRules.requireActive(project.getStatus());
                requireRoom(project);
                DesignProjectAssetJpaEntity asset = assets.saveAndFlush(new DesignProjectAssetJpaEntity(projectId,
                        file.file().key(), file.file().originalName(),
                        ContentTypePolicy.normalise(file.file().contentType()), file.file().sizeBytes(), file.sha256(),
                        file.dimensions().width(), file.dimensions().height(), key));
                projects.record(project, actor, ProjectEventType.ASSET_ADDED,
                        asset.getId() + " " + asset.getOriginalName());
                prepared.adopt();
                return view(asset);
            });
        }
    }

    /**
     * Saves an image of one of the project's designs into the library (FE request 2026-11-13 E-2): the stored file is
     * copied, as placing a library file on a design copies it the other way ({@link DesignAssetService#placeFromLibrary}),
     * so neither copy depends on the other. The design must be the caller's and in this project, and the image that
     * design's own; anything else does not exist. Saving the same image again answers with the file it made, even once
     * the library is full or closed.
     */
    @Auditable(action = AuditAction.UPDATE, resourceType = "design-project", resourceId = "#projectId")
    public LibraryAssetView saveFromDesign(UUID projectId, UUID actor, UUID designId, UUID designAssetId) {
        DesignProjectJpaEntity project = projects.lock(projectId, actor);
        DesignDraftJpaEntity draft = drafts.findById(designId)
                .filter(found -> projectId.equals(found.getProjectId()))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        CustomerDesignRules.requireOwner(actor, draft.getOwnerUserId(), draft.getStatus());
        DesignAssetJpaEntity image = designAssets.findByDraftIdAndId(designId, designAssetId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        String key = actor + ":library:design:" + designId + ":" + designAssetId;
        Optional<DesignProjectAssetJpaEntity> saved = assets.findByProjectIdAndUploadKey(projectId, key);
        if (saved.isPresent()) {
            return view(saved.get());
        }
        DesignProjectRules.requireActive(project.getStatus());
        requireRoom(project);
        if (!LIBRARY_TYPES.contains(image.getContentType())) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_MEDIA_TYPE,
                    "A design project's library takes PNG, JPEG and WebP images");
        }
        String copy = files.copy(image.getStorageKey(), FileCategory.DESIGN_ASSET, image.getContentType());
        DesignProjectAssetJpaEntity asset = assets.saveAndFlush(new DesignProjectAssetJpaEntity(projectId, copy,
                image.getOriginalName(), image.getContentType(), image.getSizeBytes(), image.getChecksum(),
                image.getWidthPx(), image.getHeightPx(), key));
        projects.record(project, actor, ProjectEventType.ASSET_ADDED,
                asset.getId() + " " + asset.getOriginalName() + " (from design " + designId + ")");
        return view(asset);
    }

    /** The library, the newest first, to the project's owner. */
    @Transactional(readOnly = true)
    public List<LibraryAssetView> list(UUID projectId, UUID actor) {
        projects.own(projectId, actor);
        return assets.findByProjectIdOrderByCreatedAtDescIdDesc(projectId).stream()
                .map(DesignProjectAssetService::view).toList();
    }

    /** A link the studio shows a library file from, to the project's owner. */
    @Transactional(readOnly = true)
    public DownloadLink link(UUID projectId, UUID assetId, UUID actor) {
        projects.own(projectId, actor);
        DesignProjectAssetJpaEntity asset = assets.findByIdAndProjectId(assetId, projectId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        return files.viewLink(asset.getStorageKey());
    }

    /**
     * Removes a file from the library of an open project (BR-18): designs it was placed on hold their own copy, and the
     * file is swept once no row names it ({@code DesignFileReferences}).
     */
    @Auditable(action = AuditAction.UPDATE, resourceType = "design-project", resourceId = "#projectId")
    public void remove(UUID projectId, UUID assetId, UUID actor) {
        DesignProjectJpaEntity project = projects.lockActive(projectId, actor);
        DesignProjectAssetJpaEntity asset = assets.findByIdAndProjectId(assetId, projectId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        assets.delete(asset);
        assets.flush();
        projects.record(project, actor, ProjectEventType.ASSET_REMOVED, asset.getId() + " " + asset.getOriginalName());
    }

    private void requireRoom(DesignProjectJpaEntity project) {
        DesignProjectRules.requireRoomForAsset(assets.countByProjectId(project.getId()));
    }

    /** The same key names the same upload: same bytes, name and type, or the key was reused for another file. */
    private static LibraryAssetView replayed(LibraryAssetView earlier, String checksum, FileUpload upload) {
        if (!earlier.checksum().equals(checksum) || !earlier.originalName().equals(upload.originalName())
                || !ContentTypePolicy.normalise(upload.contentType()).equals(earlier.contentType())) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        return earlier;
    }

    static LibraryAssetView view(DesignProjectAssetJpaEntity a) {
        return new LibraryAssetView(a.getId(), a.getProjectId(), a.getOriginalName(), a.getContentType(),
                a.getSizeBytes(), a.getChecksum(), a.getWidthPx(), a.getHeightPx(), a.getCreatedAt());
    }
}
