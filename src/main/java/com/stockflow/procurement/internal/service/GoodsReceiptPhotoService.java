package com.stockflow.procurement.internal.service;

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
import com.stockflow.procurement.internal.domain.GoodsReceiptPhotoStage;
import com.stockflow.procurement.internal.domain.GoodsReceiptStatus;
import com.stockflow.procurement.internal.entity.GoodsReceiptPhotoJpaEntity;
import com.stockflow.procurement.internal.repository.GoodsReceiptPhotoJpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Photos taken while goods are received (04b F-RCV step 05: the warehouse, of what arrived, while the receipt is
 * counted). Photos QC took while receiving QC existed stay, as stage {@code QC}; no step takes one any more (kltn-docs
 * 03 BR-07). Evidence for a dispute with the producer: kept
 * append-only ({@code procurement.goods_receipt_photos}), private, shown through display copies without the phone's
 * metadata ({@link FileCategory#QC_EVIDENCE}).
 *
 * <p>A photo is checked, scanned and stored the way every upload is - sent through the backend or straight to the
 * object store ({@link IncomingFiles}) - with no transaction held while that happens; a short transaction then locks
 * the receipt, checks again that the step is still open and that the photo fits, and records it.</p>
 */
@Service
@Transactional
public class GoodsReceiptPhotoService {

    /**
     * Photos one subject may hold: the delivery as a whole, or one line. Enough to show every
     * side of a damaged pallet; a bound so a phone left uploading cannot fill a receipt with hundreds.
     */
    public static final int MAX_PHOTOS_PER_SUBJECT = 10;

    /** How long a link to a photo works: opened right away by the screen that asked for it. */
    private static final Duration LINK_LIFETIME = Duration.ofMinutes(5);

    /** One photo as a receipt lists it; {@code thumbnailUrl} is absent where the storage signs no browser links. */
    public record PhotoView(UUID id, UUID receiptId, UUID receiptLineId, GoodsReceiptPhotoStage stage,
                            String fileName, String contentType, long sizeBytes, UUID takenBy, Instant takenAt,
                            String thumbnailUrl) {
    }

    private final GoodsReceiptPhotoJpaRepository photos;
    private final FileTransfers files;
    private final StagedUploads staged;
    private final TransactionOperations tx;

    public GoodsReceiptPhotoService(GoodsReceiptPhotoJpaRepository photos, FileTransfers files, StagedUploads staged,
                                    TransactionOperations tx) {
        this.photos = photos;
        this.files = files;
        this.staged = staged;
        this.tx = tx;
    }

    /**
     * A slot to upload a photo of this receipt straight to the object store: only while the step takes photos and the
     * subject has room, so nobody uploads what could not be kept.
     */
    @Transactional(readOnly = true)
    public UploadSlot beginUpload(UUID receiptId, UUID receiptLineId, GoodsReceiptPhotoStage stage,
                                  UploadSlotRequest request, UUID by) {
        requireOpen(receiptId, receiptLineId, stage, false);
        return staged.begin(FileCategory.QC_EVIDENCE, by, request);
    }

    /**
     * Keeps one photo. Not transactional itself: the file is prepared first. A browser upload named again - also after
     * a commit whose outcome the first request never learned - answers with the photo it made.
     */
    @Auditable(action = AuditAction.CREATE, resourceType = "goods-receipt-photo", resourceId = "#result?.id()",
            details = "{receiptId: #receiptId, lineId: #receiptLineId, stage: #stage}")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public PhotoView add(UUID receiptId, UUID receiptLineId, GoodsReceiptPhotoStage stage, IncomingFiles photo,
                         UUID by) {
        Objects.requireNonNull(stage, "stage");
        if (by == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        if (photo.count() != 1) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Send exactly one photo");
        }
        if (photo instanceof IncomingFiles.Uploaded uploaded) {
            // The key is the upload's own, so a row naming it is the photo this upload made.
            Optional<String> key = staged.keyOf(FileCategory.QC_EVIDENCE, by, uploaded.uploadIds().get(0));
            Optional<PhotoView> earlier = key.flatMap(k -> tx.execute(status -> {
                requireReceipt(receiptId, false);
                return photos.findByStorageKey(k)
                        .filter(found -> found.getReceiptId().equals(receiptId)
                                && Objects.equals(found.getReceiptLineId(), receiptLineId) && found.getStage() == stage)
                        .map(this::view);
            }));
            if (earlier.isPresent()) {
                return earlier.get();
            }
        }
        tx.executeWithoutResult(status -> requireOpen(receiptId, receiptLineId, stage, false));
        try (PreparedFiles prepared = photo.prepare(FileCategory.QC_EVIDENCE, files, staged)) {
            PreparedFiles.PreparedFile file = prepared.single();
            StoredFile stored = file.file();
            return tx.execute(status -> {
                requireOpen(receiptId, receiptLineId, stage, true);
                var saved = photos.saveAndFlush(new GoodsReceiptPhotoJpaEntity(Identifiers.newId(), receiptId,
                        receiptLineId, stage, stored.key(), stored.originalName(), stored.contentType(),
                        stored.sizeBytes(), file.sha256(), by));
                prepared.adopt();
                return view(saved);
            });
        }
    }

    /** Every photo of the receipt, oldest first: the delivery's and each line's, legacy QC photos included. */
    @Transactional(readOnly = true)
    public List<PhotoView> list(UUID receiptId) {
        requireReceipt(receiptId, false);
        return photos.findByReceiptIdOrderByIdAsc(receiptId).stream().map(this::view).toList();
    }

    /**
     * A link showing one photo: its display copy, upright and without the phone's metadata; or, with
     * {@code original}, the photo exactly as taken, saved under its name - audited, as it is evidence and may say where
     * it was taken.
     */
    @Transactional(readOnly = true)
    public DownloadLink viewLink(UUID receiptId, UUID photoId) {
        return files.evidenceViewLink(photo(receiptId, photoId).getStorageKey(), LINK_LIFETIME);
    }

    @Transactional(readOnly = true)
    @Auditable(action = AuditAction.EXPORT, resourceType = "goods-receipt-photo", resourceId = "#photoId",
            details = "{receiptId: #receiptId, file: 'original'}")
    public DownloadLink originalLink(UUID receiptId, UUID photoId) {
        var photo = photo(receiptId, photoId);
        return files.downloadLink(photo.getStorageKey(), photo.getOriginalName());
    }

    private GoodsReceiptPhotoJpaEntity photo(UUID receiptId, UUID photoId) {
        requireReceipt(receiptId, false);
        return photos.findByIdAndReceiptId(photoId, receiptId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "No photo " + photoId));
    }

    /**
     * The receipt is in the caller's warehouses, the step takes photos now, the line is a line of this receipt, and the
     * subject has room for one more. With {@code lock}, the receipt row is held until commit, so neither a status
     * change nor a parallel photo can slip between this check and the insert.
     */
    private void requireOpen(UUID receiptId, UUID receiptLineId, GoodsReceiptPhotoStage stage, boolean lock) {
        Objects.requireNonNull(stage, "stage");
        GoodsReceiptStatus status = requireReceipt(receiptId, lock);
        if (receiptLineId != null && !photos.isLineOf(receiptLineId, receiptId)) {
            throw new BusinessException(ErrorCode.RECEIPT_LINE_NOT_FOUND, "No line " + receiptLineId);
        }
        if (!stage.openAt(status)) {
            throw new BusinessException(ErrorCode.INVALID_RECEIPT_TRANSITION, stage == GoodsReceiptPhotoStage.QC
                    ? "QC photos are no longer taken: there is no quality check at the warehouse"
                    : "Receiving photos are taken while the receipt is being counted or awaits approval");
        }
        long kept = receiptLineId == null
                ? photos.countByReceiptIdAndStageAndReceiptLineIdIsNull(receiptId, stage)
                : photos.countByReceiptIdAndStageAndReceiptLineId(receiptId, stage, receiptLineId);
        if (kept >= MAX_PHOTOS_PER_SUBJECT) {
            throw new BusinessException(ErrorCode.GOODS_RECEIPT_PHOTO_LIMIT_REACHED,
                    "At most " + MAX_PHOTOS_PER_SUBJECT + " photos here");
        }
    }

    /** The receipt exists and is of a warehouse the caller may act in (BR-SEC-002); its status. */
    private GoodsReceiptStatus requireReceipt(UUID receiptId, boolean lock) {
        var facts = (receiptId == null ? Optional.<GoodsReceiptPhotoJpaRepository.ReceiptFacts>empty()
                : lock ? photos.lockReceipt(receiptId) : photos.receiptFacts(receiptId))
                .orElseThrow(() -> new BusinessException(ErrorCode.GOODS_RECEIPT_NOT_FOUND, "No receipt " + receiptId));
        WarehouseScope.requireWarehouse(facts.getWarehouseId());
        return GoodsReceiptStatus.valueOf(facts.getStatus());
    }

    private PhotoView view(GoodsReceiptPhotoJpaEntity p) {
        return new PhotoView(p.getId(), p.getReceiptId(), p.getReceiptLineId(), p.getStage(), p.getOriginalName(),
                p.getContentType(), p.getSizeBytes(), p.getTakenBy(), p.getTakenAt(),
                files.evidenceThumbnailLink(p.getStorageKey()).map(DownloadLink::url).orElse(null));
    }
}
