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
    private final ObjectLockProperties objectLock;

    /** Without Object Lock: the shape the storage tests construct. */
    public FileTransfers(FileStorage storage, StorageProperties properties, Clock clock) {
        this(storage, properties, clock, ObjectLockProperties.disabled());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public FileTransfers(FileStorage storage, StorageProperties properties, Clock clock,
                         ObjectLockProperties objectLock) {
        this.storage = storage;
        this.properties = properties;
        this.clock = clock;
        this.objectLock = objectLock;
    }

    public StoredFile store(FileCategory category, FileUpload upload) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("File attachment requires an active database transaction");
        }
        StoredFile file = storage.store(category, upload.originalName(), upload.contentType(),
                upload.sizeBytes(), upload.content(), upload.sha256());
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

    /**
     * Makes an approved product rendition reachable through the CDN by copying it to its public key,
     * and returns that key. Must run inside the approving transaction: if the approval rolls back,
     * a copy this call created is deleted again, so nothing becomes public for an approval that
     * did not happen. A copy that already existed is never deleted - an earlier approval owns it.
     */
    public String publish(String renditionKey) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Publishing requires an active database transaction");
        }
        String publicKey = StorageKeys.publishedKeyOf(renditionKey);
        if (publicKey.equals(renditionKey) || storage.exists(publicKey)) {
            return publicKey;
        }
        storage.copy(renditionKey, publicKey);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    try {
                        storage.delete(publicKey);
                    } catch (RuntimeException cleanupFailure) {
                        log.error("Published copy of an unapproved image requires cleanup: {}", publicKey,
                                cleanupFailure);
                    }
                }
            }
        });
        return publicKey;
    }

    /**
     * Locks {@code key} with S3 Object Lock for the period of {@code record} once the current
     * transaction commits - never before, because an object locked by a transaction that then rolls
     * back could be neither deleted nor ever referenced. Does nothing while
     * {@code stockflow.storage.object-lock.enabled} is off.
     *
     * <p>A lock that fails after the commit cannot undo it: the record stands, protected by
     * versioning alone, and the failure is logged at ERROR naming the key so it can be locked by hand.
     * Failing the request instead would report as failed a business action that has happened.</p>
     */
    public void retainAfterCommit(String key, RecordRetention record) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Retention requires an active database transaction");
        }
        StorageKeys.requireValid(key);
        if (!objectLock.enabled()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    storage.retain(key, clock.instant().plus(objectLock.periodOf(record)));
                } catch (RuntimeException lockFailure) {
                    log.error("Object Lock was not applied; {} record {} is protected by versioning only",
                            record, key, lockFailure);
                }
            }
        });
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
