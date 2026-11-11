package com.stockflow.common.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Coordinates existing storage with database transactions; does not implement storage policy. */
@Component
public class FileTransfers {
    private static final Logger log = LoggerFactory.getLogger(FileTransfers.class);
    private final FileStorage storage;
    private final StorageProperties properties;
    private final Clock clock;
    private final ObjectLockProperties objectLock;
    private final UploadInspection inspection;
    private final ImageRenditions images;

    /** Without Object Lock or scanning: the shape the storage tests construct. */
    public FileTransfers(FileStorage storage, StorageProperties properties, Clock clock) {
        this(storage, properties, clock, ObjectLockProperties.disabled());
    }

    /** Without scanning. */
    public FileTransfers(FileStorage storage, StorageProperties properties, Clock clock,
                         ObjectLockProperties objectLock) {
        this(storage, properties, clock, objectLock, new UploadInspection("localhost", 3310, false));
    }

    public FileTransfers(FileStorage storage, StorageProperties properties, Clock clock,
                         ObjectLockProperties objectLock, UploadInspection inspection) {
        this(storage, properties, clock, objectLock, inspection, new ImageRenditions());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public FileTransfers(FileStorage storage, StorageProperties properties, Clock clock,
                         ObjectLockProperties objectLock, UploadInspection inspection, ImageRenditions images) {
        this.images = images;
        this.storage = storage;
        this.properties = properties;
        this.clock = clock;
        this.objectLock = objectLock;
        this.inspection = inspection;
    }

    /**
     * Checks, scans and stores {@code uploads} with no transaction and no lock held, for a short
     * transaction to attach afterwards ({@link PreparedFiles}). Each upload is checked against the
     * category (type by its first bytes, size), scanned, and written with its SHA-256 declared, so the
     * store refuses bytes that changed on the way.
     *
     * <p>Several uploads are prepared side by side, so five photos take about as long as the slowest
     * rather than the five added up; the scanner's own limit still bounds how many are scanned at
     * once. All or nothing: when one fails, those already stored are deleted again and the first
     * failure, in upload order, is thrown.</p>
     *
     * @throws com.stockflow.common.error.BusinessException {@code UNSUPPORTED_MEDIA_TYPE},
     *         {@code PAYLOAD_TOO_LARGE}, {@code VALIDATION_FAILED} (also a detection), {@code RATE_LIMITED}
     * @throws StorageException when the scanner or the store cannot be reached
     */
    public PreparedFiles prepare(FileCategory category, List<FileUpload> uploads) {
        if (uploads.size() <= 1) {
            return new PreparedFiles(storage, uploads.stream().map(upload -> prepareOne(category, upload)).toList());
        }
        List<Future<PreparedFiles.PreparedFile>> pending = new ArrayList<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (FileUpload upload : uploads) {
                pending.add(executor.submit(() -> prepareOne(category, upload)));
            }
        }
        List<PreparedFiles.PreparedFile> prepared = new ArrayList<>();
        RuntimeException failure = null;
        for (Future<PreparedFiles.PreparedFile> result : pending) {
            try {
                prepared.add(result.get());
            } catch (ExecutionException ex) {
                if (failure == null) {
                    failure = ex.getCause() instanceof RuntimeException runtime ? runtime
                            : new StorageException("Could not prepare an upload", ex.getCause());
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                if (failure == null) {
                    failure = new StorageException("Interrupted while preparing uploads", interrupted);
                }
            }
        }
        if (failure != null) {
            new PreparedFiles(storage, prepared).close();
            throw failure;
        }
        return new PreparedFiles(storage, prepared);
    }

    /** {@link #prepare(FileCategory, List)} of one upload. */
    public PreparedFiles prepare(FileCategory category, FileUpload upload) {
        return prepare(category, List.of(upload));
    }

    private PreparedFiles.PreparedFile prepareOne(FileCategory category, FileUpload upload) {
        try (BufferedUpload buffered = BufferedUpload.read(category, upload)) {
            try (InputStream content = buffered.open()) {
                inspection.requireClean(content);
            }
            PdfContentPolicy.check(category, upload.contentType(), buffered.path());
            ImageDimensions.Size dimensions = ImageDimensions.measures(category)
                    ? ImageDimensions.of(buffered.path()) : null;
            StoredFile file;
            try (InputStream content = new BufferedInputStream(buffered.open())) {
                file = storage.store(category, upload.originalName(), upload.contentType(),
                        upload.sizeBytes(), content, buffered.checksum());
            }
            if (!StorageKeys.hasDisplayCopies(category)) {
                return new PreparedFiles.PreparedFile(file, buffered.checksum(), List.of(), dimensions);
            }
            try {
                // From the spooled file, read once a decoder is free: a photo waiting for one holds no heap.
                List<String> copies = EvidenceCopies.store(storage, images, category, file.key(), buffered.path());
                return new PreparedFiles.PreparedFile(file, buffered.checksum(), copies);
            } catch (RuntimeException failure) {
                storage.delete(file.key());
                throw failure;
            }
        } catch (IOException unreadable) {
            throw new StorageException("Could not read the buffered upload", unreadable);
        }
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
     * Copies a stored file to a new key of {@code category}, inside the caller's transaction, and returns that key: if
     * the transaction rolls back, the copy is deleted again. Server-side where the store supports it. For a file one
     * record hands to another that must own its own copy - a design project's library file placed on a design, which
     * keeps its copy when the file leaves the library (kltn-docs 12 BR-18).
     */
    public String copy(String sourceKey, FileCategory category, String contentType) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Copying a file requires an active database transaction");
        }
        String target = StorageKeys.generate(category, contentType, clock.instant());
        storage.copy(sourceKey, target);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                // An unknown commit outcome may already reference this object: preserve it.
                if (status == STATUS_ROLLED_BACK) {
                    try {
                        storage.delete(target);
                    } catch (RuntimeException cleanupFailure) {
                        log.error("Orphaned copy requires cleanup: {}", target, cleanupFailure);
                    }
                }
            }
        });
        return target;
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

    /**
     * A link that downloads the file under {@code fileName} ({@code Content-Disposition: attachment}),
     * for a document someone saves: a print file, an invoice scan. Checks first that the object is
     * there, so a missing file is an error here rather than a broken link in the browser. The name keeps the uploader's
     * words but takes its extension from what was stored ({@link StorageKeys#downloadName}).
     *
     * <p>Call only after resolving the attachment through its owning business record.</p>
     */
    public DownloadLink downloadLink(String key, String fileName) {
        if (!storage.exists(key)) {
            throw new StorageException("Attached object is missing");
        }
        return sign(key, properties.presignedUrlTtl(),
                DownloadDisposition.attachment(StorageKeys.downloadName(fileName, key)));
    }

    /**
     * {@link #downloadLink(String, String)} without a file name: the browser decides from the
     * response how to handle it.
     */
    public DownloadLink downloadLink(String key) {
        if (!storage.exists(key)) {
            throw new StorageException("Attached object is missing");
        }
        return sign(key, properties.presignedUrlTtl(), null);
    }

    /**
     * A link to show the file in the page ({@code inline}): an image in a gallery, a photo in a list.
     * No existence check - signing is a local computation and a list of twenty photos must not cost
     * twenty round trips to the store; a file that is gone shows as a broken image. The browser may
     * cache the response for as long as the link is valid.
     *
     * <p>Call only after resolving the attachment through its owning business record.</p>
     */
    public DownloadLink viewLink(String key) {
        return viewLink(key, properties.presignedUrlTtl());
    }

    /** {@link #viewLink(String)} valid for {@code validFor}, never longer than the configured link lifetime. */
    public DownloadLink viewLink(String key, Duration validFor) {
        Duration ttl = validFor.compareTo(properties.presignedUrlTtl()) > 0 ? properties.presignedUrlTtl() : validFor;
        return sign(key, ttl, DownloadDisposition.inline(ttl));
    }

    /**
     * A link showing an evidence photo: its large display copy - no metadata, upright, a fraction of the size - or,
     * for a photo stored before copies were made or one that could not be rendered, the photo as uploaded. One HEAD
     * request to choose; for lists use {@link #evidenceThumbnailLink}.
     */
    public DownloadLink evidenceViewLink(String originalKey) {
        return evidenceViewLink(originalKey, properties.presignedUrlTtl());
    }

    /** {@link #evidenceViewLink(String)} valid for {@code validFor}, never longer than the configured lifetime. */
    public DownloadLink evidenceViewLink(String originalKey, Duration validFor) {
        String copy = StorageKeys.evidenceCopyOf(originalKey, EvidenceCopies.DISPLAY);
        return viewLink(storage.exists(copy) ? copy : originalKey, validFor);
    }

    /**
     * The thumbnail of an evidence photo, for a list, or empty when this storage provider signs no browser links (the
     * filesystem one). Signed without asking the store whether it exists: a photo stored before thumbnails were made
     * shows as a broken image, and the client falls back to {@link #evidenceViewLink} through the photo's own link.
     */
    public java.util.Optional<DownloadLink> evidenceThumbnailLink(String originalKey) {
        return tryViewLink(StorageKeys.evidenceCopyOf(originalKey, EvidenceCopies.THUMBNAIL));
    }

    /** {@link #viewLink(String)}, or empty when this storage provider signs no browser links. */
    public java.util.Optional<DownloadLink> tryViewLink(String key) {
        try {
            return java.util.Optional.of(viewLink(key));
        } catch (StorageException unsignable) {
            return java.util.Optional.empty();
        }
    }

    private DownloadLink sign(String key, Duration validFor, DownloadDisposition disposition) {
        var expiresAt = clock.instant().plus(validFor);
        String url = storage.presignedDownloadUrl(key, validFor, disposition);
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            throw new StorageException("Browser downloads require the S3/MinIO storage provider");
        }
        return new DownloadLink(url, expiresAt);
    }
}
