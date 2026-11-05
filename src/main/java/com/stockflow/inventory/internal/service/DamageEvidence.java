package com.stockflow.inventory.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.storage.DownloadLink;
import com.stockflow.common.storage.FileCategory;
import com.stockflow.common.storage.FileTransfers;
import com.stockflow.common.storage.FileUpload;
import com.stockflow.common.storage.PreparedFiles;
import com.stockflow.inventory.internal.domain.StockDamageReport;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

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

    DamageEvidence(FileTransfers files) {
        this.files = files;
    }

    /**
     * Checks the count, then checks, scans and stores each photo. Needs no transaction: the caller adopts the result
     * in the report's.
     *
     * @throws BusinessException {@code DAMAGE_PHOTO_REQUIRED} for none; {@code VALIDATION_FAILED} for more than
     *         {@value StockDamageReport#MAX_PHOTOS}; {@code UNSUPPORTED_MEDIA_TYPE}, {@code PAYLOAD_TOO_LARGE}
     */
    PreparedFiles prepare(List<FileUpload> uploads) {
        if (uploads == null || uploads.isEmpty()) {
            throw new BusinessException(ErrorCode.DAMAGE_PHOTO_REQUIRED,
                    "Damaged stock is reported with at least one photo (BR-STK-003)");
        }
        if (uploads.size() > StockDamageReport.MAX_PHOTOS) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "A damage report takes at most %d photos".formatted(StockDamageReport.MAX_PHOTOS));
        }
        return files.prepare(FileCategory.QC_EVIDENCE, uploads);
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
        return files.viewLink(storageKey);
    }

    /** {@code image/JPEG; charset=...} as the category accepted it: the table lists the bare lower-case type. */
    private static String normalised(String contentType) {
        int semicolon = contentType.indexOf(';');
        return (semicolon < 0 ? contentType : contentType.substring(0, semicolon)).trim().toLowerCase(Locale.ROOT);
    }
}
