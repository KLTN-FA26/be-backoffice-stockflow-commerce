package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.WarehouseScope;
import com.stockflow.common.storage.DownloadLink;
import com.stockflow.common.storage.FileCategory;
import com.stockflow.common.storage.FileTransfers;
import com.stockflow.common.storage.IncomingFiles;
import com.stockflow.common.storage.PreparedFiles;
import com.stockflow.common.storage.StagedUploads;
import com.stockflow.common.storage.StoredFile;
import com.stockflow.common.storage.UploadSlot;
import com.stockflow.common.storage.UploadSlotRequest;
import com.stockflow.fulfillment.internal.domain.LoadStatus;
import com.stockflow.fulfillment.internal.entity.DispatchLoadJpaEntity;
import com.stockflow.fulfillment.internal.entity.LoadWaybillFileJpaEntity;
import com.stockflow.fulfillment.internal.repository.DispatchLoadJpaRepository;
import com.stockflow.fulfillment.internal.repository.LoadWaybillFileJpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The carrier's waybill documents kept with a load (3-2; kltn-docs 09 §4.4, BR-28: "file vận đơn - PDF / ảnh / Excel
 * của hãng - đính kèm chuyến: tuỳ chọn"; customers never see them). In the {@code documents} category: PDF, Word, Excel
 * or a photo of a paper waybill.
 *
 * <p>Checked, scanned and stored as every upload is - through the backend or straight to the object store - with no
 * transaction held while that happens; a short transaction then records it, if the load is still live or left. A
 * cancelled load takes no new file: its booking is history.</p>
 */
@Service
@Transactional
public class LoadWaybillFiles {

    /** A file as a load lists it; the bytes come from {@link #downloadLink}. */
    public record WaybillFile(UUID id, UUID loadId, String fileName, String contentType, long sizeBytes,
                              UUID uploadedBy, Instant uploadedAt) {
    }

    private final DispatchLoadJpaRepository loads;
    private final LoadWaybillFileJpaRepository files;
    private final FileTransfers transfers;
    private final StagedUploads staged;
    private final TransactionOperations tx;
    private final Clock clock;

    public LoadWaybillFiles(DispatchLoadJpaRepository loads, LoadWaybillFileJpaRepository files,
                            FileTransfers transfers, StagedUploads staged, TransactionOperations tx, Clock clock) {
        this.loads = loads;
        this.files = files;
        this.transfers = transfers;
        this.staged = staged;
        this.tx = tx;
        this.clock = clock;
    }

    /** A slot to upload a waybill file of this load straight to the object store. */
    @Transactional(readOnly = true)
    public UploadSlot beginUpload(UUID loadId, UploadSlotRequest request, UUID by) {
        requireAttachable(loadId);
        return staged.begin(FileCategory.DOCUMENT, by, request);
    }

    /**
     * Keeps one file with the load. Not transactional itself: the file is prepared first. A browser upload named again
     * answers with the file it made.
     *
     * @throws BusinessException {@code LOAD_NOT_FOUND}; {@code OUT_OF_DATA_SCOPE}; {@code INVALID_LOAD_TRANSITION} for
     *         a cancelled load; {@code VALIDATION_FAILED} unless exactly one file
     */
    @Auditable(action = AuditAction.CREATE, resourceType = "load-waybill-file", resourceId = "#result?.id()",
            details = "{loadId: #loadId}")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public WaybillFile attach(UUID loadId, IncomingFiles file, UUID by) {
        if (by == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        if (file.count() != 1) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Attach exactly one waybill file");
        }
        if (file instanceof IncomingFiles.Uploaded uploaded) {
            Optional<String> key = staged.keyOf(FileCategory.DOCUMENT, by, uploaded.uploadIds().get(0));
            Optional<WaybillFile> earlier = key.flatMap(k -> tx.execute(status -> {
                requireLoad(loadId);
                return files.findByStorageKeyAndLoadId(k, loadId).map(LoadWaybillFiles::view);
            }));
            if (earlier.isPresent()) {
                return earlier.get();
            }
        }
        tx.executeWithoutResult(status -> requireAttachable(loadId));
        try (PreparedFiles prepared = file.prepare(FileCategory.DOCUMENT, transfers, staged)) {
            StoredFile stored = prepared.single().file();
            return tx.execute(status -> {
                requireAttachable(loadId);
                LoadWaybillFileJpaEntity saved = files.saveAndFlush(new LoadWaybillFileJpaEntity(Identifiers.newId(),
                        loadId, stored.key(), stored.originalName(), stored.contentType(), stored.sizeBytes(), by,
                        clock.instant()));
                prepared.adopt();
                return view(saved);
            });
        }
    }

    @Transactional(readOnly = true)
    public List<WaybillFile> list(UUID loadId) {
        requireLoad(loadId);
        return files.findByLoadIdOrderByUploadedAtAscIdAsc(loadId).stream().map(LoadWaybillFiles::view).toList();
    }

    /** A link saving the file under its name; audited, as a carrier's document leaves the system. */
    @Transactional(readOnly = true)
    @Auditable(action = AuditAction.EXPORT, resourceType = "load-waybill-file", resourceId = "#fileId",
            details = "{loadId: #loadId}")
    public DownloadLink downloadLink(UUID loadId, UUID fileId) {
        requireLoad(loadId);
        LoadWaybillFileJpaEntity found = files.findByIdAndLoadId(fileId, loadId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "No waybill file " + fileId));
        return transfers.downloadLink(found.getStorageKey(), found.getFileName());
    }

    private DispatchLoadJpaEntity requireLoad(UUID loadId) {
        DispatchLoadJpaEntity load = loads.findById(loadId)
                .orElseThrow(() -> new BusinessException(ErrorCode.LOAD_NOT_FOUND));
        WarehouseScope.requireWarehouse(load.getWarehouseId());
        return load;
    }

    private void requireAttachable(UUID loadId) {
        DispatchLoadJpaEntity load = requireLoad(loadId);
        if (load.getStatus() == LoadStatus.CANCELLED) {
            throw new BusinessException(ErrorCode.INVALID_LOAD_TRANSITION,
                    "Load %s is cancelled: its waybill files are history".formatted(load.getCode()));
        }
    }

    private static WaybillFile view(LoadWaybillFileJpaEntity f) {
        return new WaybillFile(f.getId(), f.getLoadId(), f.getFileName(), f.getContentType(), f.getSizeBytes(),
                f.getUploadedBy(), f.getUploadedAt());
    }
}
