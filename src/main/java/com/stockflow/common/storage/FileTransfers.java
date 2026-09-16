package com.stockflow.common.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;

/** Coordinates existing storage with database transactions; does not implement storage policy. */
@Component
public class FileTransfers {
    private static final Logger log = LoggerFactory.getLogger(FileTransfers.class);
    private final FileStorage storage;
    private final StorageProperties properties;
    private final Clock clock;

    public FileTransfers(FileStorage storage, StorageProperties properties, Clock clock) {
        this.storage = storage;
        this.properties = properties;
        this.clock = clock;
    }

    public StoredFile store(FileCategory category, FileUpload upload) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("File attachment requires an active database transaction");
        }
        StoredFile file = storage.store(category, upload.originalName(), upload.contentType(),
                upload.sizeBytes(), upload.content());
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                // An unknown commit outcome may already reference this object: preserve it.
                if (status == STATUS_ROLLED_BACK) {
                    try {
                        storage.delete(file.key());
                    } catch (RuntimeException cleanupFailure) {
                        log.error("Orphaned upload requires cleanup: {}", file.key(), cleanupFailure);
                    }
                }
            }
        });
        return file;
    }

    /** Call only after resolving the attachment through its owning business record. */
    public DownloadLink downloadLink(String key) {
        if (!storage.exists(key)) {
            throw new StorageException("Attached object is missing");
        }
        var expiresAt = clock.instant().plus(properties.presignedUrlTtl());
        String url = storage.presignedDownloadUrl(key, properties.presignedUrlTtl());
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            throw new StorageException("Browser downloads require the S3/MinIO storage provider");
        }
        return new DownloadLink(url, expiresAt);
    }
}
