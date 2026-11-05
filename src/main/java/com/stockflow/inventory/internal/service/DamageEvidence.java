package com.stockflow.inventory.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.storage.DownloadLink;
import com.stockflow.common.storage.FileCategory;
import com.stockflow.common.storage.FileTransfers;
import com.stockflow.common.storage.IncomingFiles;
import com.stockflow.common.storage.PreparedFiles;
import com.stockflow.common.storage.StagedUploads;
import com.stockflow.common.storage.UploadSlot;
import com.stockflow.common.storage.UploadSlotRequest;
import com.stockflow.inventory.internal.domain.StockDamageReport;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The photos of damaged stock (BR-STK-003), through the platform's storage and nothing of its own.
 *
 * <p>{@link FileCategory#QC_EVIDENCE}: photographs taken as evidence of the goods' condition, kept like the receipt
 * and QC photos - private, JPEG or PNG, checked by their first bytes, scanned, stored with their SHA-256. They are
 * prepared side by side and with no transaction ({@link FileTransfers#prepare}); the report's transaction adopts them
 * ({@link PreparedFiles#adopt}), so a report that fails after its photos were stored - a refused quantity, a lock
 * timeout, a second photo the store rejects - leaves no object behind and no stock moved.</p>
 */
@Component
class DamageEvidence {

    private final FileTransfers files;
    private final StagedUploads staged;

    DamageEvidence(FileTransfers files, StagedUploads staged) {
        this.files = files;
        this.staged = staged;
    }

    /**
     * Checks the count, then checks, scans and stores each photo. Needs no transaction: the caller adopts the result
     * in the report's.
     *
     * @throws BusinessException {@code DAMAGE_PHOTO_REQUIRED} for none; {@code VALIDATION_FAILED} for more than
     *         {@value StockDamageReport#MAX_PHOTOS}; {@code UNSUPPORTED_MEDIA_TYPE}, {@code PAYLOAD_TOO_LARGE}
     */
    PreparedFiles prepare(IncomingFiles uploads) {
        if (uploads == null || uploads.count() == 0) {
            throw new BusinessException(ErrorCode.DAMAGE_PHOTO_REQUIRED,
                    "Damaged stock is reported with at least one photo (BR-STK-003)");
        }
        if (uploads.count() > StockDamageReport.MAX_PHOTOS) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "A damage report takes at most %d photos".formatted(StockDamageReport.MAX_PHOTOS));
        }
        return uploads.prepare(FileCategory.QC_EVIDENCE, files, staged);
    }

    /** A slot for one photo uploaded straight to the object store ({@link StagedUploads}). */
    UploadSlot beginUpload(UUID ownerId, UploadSlotRequest request) {
        return staged.begin(FileCategory.QC_EVIDENCE, ownerId, request);
    }

    /** The report's photos, in the order they were sent. */
    List<StockDamageReport.Photo> photos(PreparedFiles prepared) {
        return prepared.files().stream().map(prepared1 -> new StockDamageReport.Photo(Identifiers.newId(),
                prepared1.file().key(), prepared1.file().originalName(), normalised(prepared1.file().contentType()),
                prepared1.file().sizeBytes(), prepared1.sha256())).toList();
    }

    /**
     * A short-lived link showing a stored photo in the page; the caller has resolved it through its report and the
     * caller's scope.
     */
    DownloadLink link(String storageKey) {
        return files.evidenceViewLink(storageKey);
    }

    /** The photo exactly as uploaded, saved under its own name. */
    DownloadLink originalLink(String storageKey, String originalName) {
        return files.downloadLink(storageKey, originalName);
    }

    /** A thumbnail link for a list, or null when this storage provider signs no browser links. */
    String thumbnail(String storageKey) {
        return files.evidenceThumbnailLink(storageKey).map(DownloadLink::url).orElse(null);
    }

    /** {@code image/JPEG; charset=...} as the category accepted it: the table lists the bare lower-case type. */
    private static String normalised(String contentType) {
        int semicolon = contentType.indexOf(';');
        return (semicolon < 0 ? contentType : contentType.substring(0, semicolon)).trim().toLowerCase(Locale.ROOT);
    }
}
