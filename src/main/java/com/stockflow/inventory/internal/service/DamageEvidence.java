package com.stockflow.inventory.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.storage.BufferedUpload;
import com.stockflow.common.storage.DownloadLink;
import com.stockflow.common.storage.FileCategory;
import com.stockflow.common.storage.FileTransfers;
import com.stockflow.common.storage.FileUpload;
import com.stockflow.common.storage.StorageException;
import com.stockflow.common.storage.StoredFile;
import com.stockflow.common.storage.UploadInspection;
import com.stockflow.inventory.internal.domain.StockDamageReport;
import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The photos of damaged stock (BR-STK-003), through the platform's storage and nothing of its own.
 *
 * <p>{@link FileCategory#QC_EVIDENCE}: photographs taken as evidence of the goods' condition, kept like the receipt
 * and QC photos - private, JPEG or PNG, checked by their first bytes ({@link BufferedUpload}), scanned
 * ({@link UploadInspection}). {@link FileTransfers#store} registers each object with the transaction, so a report that
 * fails after its photos were stored - a refused quantity, a lock timeout, a second photo the store rejects - leaves
 * no object behind and no stock moved.</p>
 */
@Component
class DamageEvidence {

    private final FileTransfers files;
    private final UploadInspection inspection;

    DamageEvidence(FileTransfers files, UploadInspection inspection) {
        this.files = files;
        this.inspection = inspection;
    }

    /**
     * Checks, scans and stores each photo, in order. Must run inside the caller's transaction.
     *
     * @throws BusinessException {@code DAMAGE_PHOTO_REQUIRED} for none; {@code VALIDATION_FAILED} for more than
     *         {@value StockDamageReport#MAX_PHOTOS}; {@code UNSUPPORTED_MEDIA_TYPE}, {@code PAYLOAD_TOO_LARGE}
     */
    List<StockDamageReport.Photo> store(List<FileUpload> uploads) {
        if (uploads == null || uploads.isEmpty()) {
            throw new BusinessException(ErrorCode.DAMAGE_PHOTO_REQUIRED,
                    "Damaged stock is reported with at least one photo (BR-STK-003)");
        }
        if (uploads.size() > StockDamageReport.MAX_PHOTOS) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "A damage report takes at most %d photos".formatted(StockDamageReport.MAX_PHOTOS));
        }
        List<StockDamageReport.Photo> stored = new ArrayList<>();
        for (FileUpload upload : uploads) {
            try (BufferedUpload buffered = BufferedUpload.read(FileCategory.QC_EVIDENCE, upload)) {
                try (InputStream content = buffered.open()) {
                    inspection.requireClean(content);
                }
                try (InputStream content = new BufferedInputStream(buffered.open())) {
                    StoredFile file = files.store(FileCategory.QC_EVIDENCE, new FileUpload(upload.originalName(),
                            upload.contentType(), upload.sizeBytes(), content));
                    stored.add(new StockDamageReport.Photo(Identifiers.newId(), file.key(), file.originalName(),
                            normalised(upload.contentType()), file.sizeBytes(), buffered.checksum()));
                }
            } catch (IOException failure) {
                throw new StorageException("Could not read a buffered damage photo", failure);
            }
        }
        return stored;
    }

    /** A short-lived link to a stored photo; the caller has resolved it through its report and the caller's scope. */
    DownloadLink link(String storageKey) {
        return files.downloadLink(storageKey);
    }

    /** {@code image/JPEG; charset=...} as the category accepted it: the table lists the bare lower-case type. */
    private static String normalised(String contentType) {
        int semicolon = contentType.indexOf(';');
        return (semicolon < 0 ? contentType : contentType.substring(0, semicolon)).trim().toLowerCase(Locale.ROOT);
    }
}
