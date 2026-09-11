package com.stockflow.common.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;

/**
 * {@link FileStorage} on S3, or on MinIO through the same API.
 *
 * <p>The default provider. MinIO speaks the S3 protocol, so development, staging and production all
 * run this class and differ only in the endpoint — which means the storage path is exercised by
 * every environment rather than only by production.</p>
 */
@Component
@ConditionalOnProperty(prefix = "stockflow.storage", name = "provider",
        havingValue = "s3", matchIfMissing = true)
class S3FileStorage implements FileStorage {

    private static final Logger log = LoggerFactory.getLogger(S3FileStorage.class);

    /** Enough to identify every signature in {@link ContentTypePolicy}. */
    private static final int SNIFF_BYTES = 12;

    private final S3Client s3;
    private final StorageProperties properties;
    private final Clock clock;

    S3FileStorage(S3Client s3, StorageProperties properties, Clock clock) {
        this.s3 = s3;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public StoredFile store(FileCategory category, String originalName, String contentType,
                            long sizeBytes, InputStream content) {
        InputStream sniffable = content.markSupported()
                ? content
                : new BufferedInputStream(content, 8192);
        try {
            sniffable.mark(SNIFF_BYTES);
            byte[] head = new byte[SNIFF_BYTES];
            int read = sniffable.readNBytes(head, 0, SNIFF_BYTES);
            sniffable.reset();

            // Validate BEFORE uploading. Checking afterwards would mean a rejected file has already
            // been written and has to be deleted - and if that delete fails, an unvalidated object
            // is left in the bucket.
            ContentTypePolicy.check(category, contentType, sizeBytes,
                    read == SNIFF_BYTES ? head : Arrays.copyOf(head, Math.max(read, 0)));

            String normalisedType = ContentTypePolicy.normalise(contentType);
            String key = StorageKeys.generate(category, normalisedType, clock.instant());

            s3.putObject(PutObjectRequest.builder()
                            .bucket(properties.bucket())
                            .key(key)
                            .contentType(normalisedType)
                            // Supplying the length lets the SDK stream rather than buffer the whole
                            // object in heap - the difference between a 50 MB upload and an OOM.
                            .contentLength(sizeBytes)
                            .build(),
                    RequestBody.fromInputStream(sniffable, sizeBytes));

            log.debug("Stored {} ({} bytes) as {}", originalName, sizeBytes, key);
            return new StoredFile(key, originalName, normalisedType, sizeBytes, clock.instant());

        } catch (IOException ex) {
            throw new StorageException("Could not read the upload stream", ex);
        } catch (S3Exception ex) {
            throw new StorageException("Object store rejected the upload", ex);
        }
    }

    @Override
    public InputStream download(String key) {
        try {
            return s3.getObject(GetObjectRequest.builder()
                    .bucket(properties.bucket())
                    .key(StorageKeys.requireValid(key))
                    .build());
        } catch (NoSuchKeyException ex) {
            throw new StorageException("No stored file with key " + key, ex);
        } catch (S3Exception ex) {
            throw new StorageException("Object store could not serve " + key, ex);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Presigning needs {@code S3Presigner}, which is a separate client from {@link S3Client}.
     * Wiring it is a small piece of configuration and is deliberately left as the one unimplemented
     * method rather than faked: returning a plain unsigned URL here would produce a link that works
     * against a public bucket and fails against a correctly private one — a difference nobody
     * discovers until the bucket is locked down.</p>
     */
    @Override
    public String presignedDownloadUrl(String key, Duration validFor) {
        throw new UnsupportedOperationException(
                "Presigned URLs need an S3Presigner bean. Add software.amazon.awssdk:s3-presigner, "
                + "build a presigner alongside the S3Client in StorageConfig, and call "
                + "presigner.presignGetObject(...). Until then, serve downloads via the "
                + "download(String) stream from a controller that checks authorisation.");
    }

    @Override
    public void delete(String key) {
        try {
            s3.deleteObject(DeleteObjectRequest.builder()
                    .bucket(properties.bucket())
                    .key(StorageKeys.requireValid(key))
                    .build());
        } catch (NoSuchKeyException alreadyGone) {
            // Idempotent by contract - nothing to do.
        } catch (S3Exception ex) {
            throw new StorageException("Object store could not delete " + key, ex);
        }
    }

    @Override
    public boolean exists(String key) {
        try {
            s3.headObject(HeadObjectRequest.builder()
                    .bucket(properties.bucket())
                    .key(StorageKeys.requireValid(key))
                    .build());
            return true;
        } catch (NoSuchKeyException absent) {
            return false;
        } catch (S3Exception ex) {
            throw new StorageException("Object store could not be reached for " + key, ex);
        }
    }
}
