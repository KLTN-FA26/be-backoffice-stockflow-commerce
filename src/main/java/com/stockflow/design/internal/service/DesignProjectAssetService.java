package com.stockflow.design.internal.service;

import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.storage.ContentTypePolicy;
import com.stockflow.common.storage.DownloadLink;
import com.stockflow.common.storage.FileCategory;
import com.stockflow.common.storage.FileTransfers;
import com.stockflow.common.storage.FileUpload;
import com.stockflow.common.storage.PreparedFiles;
import com.stockflow.design.internal.domain.DesignProjectRules;
import com.stockflow.design.internal.domain.ProjectEventType;
import com.stockflow.design.internal.entity.DesignProjectAssetJpaEntity;
import com.stockflow.design.internal.entity.DesignProjectJpaEntity;
import com.stockflow.design.internal.repository.DesignProjectAssetJpaRepository;
import java.time.Instant;
import java.util.List;
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
 */
@Service
@Transactional
public class DesignProjectAssetService {

    private final DesignProjectService projects;
    private final DesignProjectAssetJpaRepository assets;
    private final FileTransfers files;
    private final TransactionOperations tx;

    public DesignProjectAssetService(DesignProjectService projects, DesignProjectAssetJpaRepository assets,
                                     FileTransfers files, TransactionOperations tx) {
        this.projects = projects;
        this.assets = assets;
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
     */
    @Auditable(action = AuditAction.UPDATE, resourceType = "design-project", resourceId = "#projectId")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public LibraryAssetView upload(UUID projectId, UUID actor, FileUpload upload) {
        tx.executeWithoutResult(status -> requireRoom(projects.lockActive(projectId, actor)));
        try (PreparedFiles prepared = files.prepare(FileCategory.DESIGN_ASSET, upload)) {
            PreparedFiles.PreparedFile file = prepared.single();
            if (file.dimensions() == null) {
                throw new IllegalStateException("A design image is measured when it is prepared");
            }
            return tx.execute(status -> {
                DesignProjectJpaEntity project = projects.lockActive(projectId, actor);
                requireRoom(project);
                DesignProjectAssetJpaEntity asset = assets.saveAndFlush(new DesignProjectAssetJpaEntity(projectId,
                        file.file().key(), file.file().originalName(),
                        ContentTypePolicy.normalise(file.file().contentType()), file.file().sizeBytes(), file.sha256(),
                        file.dimensions().width(), file.dimensions().height()));
                projects.record(project, actor, ProjectEventType.ASSET_ADDED,
                        asset.getId() + " " + asset.getOriginalName());
                prepared.adopt();
                return view(asset);
            });
        }
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

    static LibraryAssetView view(DesignProjectAssetJpaEntity a) {
        return new LibraryAssetView(a.getId(), a.getProjectId(), a.getOriginalName(), a.getContentType(),
                a.getSizeBytes(), a.getChecksum(), a.getWidthPx(), a.getHeightPx(), a.getCreatedAt());
    }
}
