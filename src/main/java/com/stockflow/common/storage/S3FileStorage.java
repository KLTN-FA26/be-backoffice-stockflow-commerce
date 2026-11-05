package com.stockflow.common.storage;

import com.stockflow.common.logging.LogSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ChecksumAlgorithm;
import software.amazon.awssdk.services.s3.model.ChecksumMode;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.MetadataDirective;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectLockRetention;
import software.amazon.awssdk.services.s3.model.ObjectLockRetentionMode;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRetentionRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;

/**
 * {@link FileStorage} on S3, or on MinIO through the same API.
 *
 * <p>The default provider. MinIO speaks the S3 protocol, so development, staging and production all
 * run this class and differ only in the endpoint — which means the storage path is exercised by
 * every environment rather than only by production.</p>
 *
 * <h2>Every object carries a store-computed SHA-256</h2>
 *
 * <p>Writes and copies ask for {@link ChecksumAlgorithm#SHA256}. The store computes it over the bytes
 * it receives, keeps it with the object, and returns it from a HEAD with {@link ChecksumMode#ENABLED}
 * ({@link #stat}). Integrity checks therefore compare metadata instead of downloading the object
 * again. When the caller knows the digest already, it is sent as {@code x-amz-checksum-sha256} and
 * the store refuses a write whose bytes differ (HTTP 400 {@code BadDigest}).</p>
 */
@Component
@ConditionalOnProperty(prefix = "stockflow.storage", name = "provider",
        havingValue = "s3", matchIfMissing = true)
class S3FileStorage implements FileStorage {

    private static final Logger log = LoggerFactory.getLogger(S3FileStorage.class);

    /** Enough to identify every signature in {@link ContentTypePolicy}. */
    private static final int SNIFF_BYTES = 12;

    /** Keys are immutable UUID paths, so a CDN may cache them without ever invalidating. */
    private static final String IMMUTABLE_PUBLIC = "public,max-age=31536000,immutable";

    private final S3Client s3;
    private final StorageProperties properties;
    private final Clock clock;
    private final S3Presigner presigner;
    private final ObjectLockProperties objectLock;

    /** Without Object Lock configuration: the shape the storage tests construct. */
    S3FileStorage(S3Client s3, StorageProperties properties, Clock clock, S3Presigner presigner) {
        this(s3, properties, clock, presigner, ObjectLockProperties.disabled());
    }

    @Autowired
    S3FileStorage(S3Client s3, StorageProperties properties, Clock clock, S3Presigner presigner,
                  ObjectLockProperties objectLock) {
        this.s3 = s3;
        this.properties = properties;
        this.clock = clock;
        this.presigner = presigner;
        this.objectLock = objectLock;
    }

    @Override
    public StoredFile store(FileCategory category, String originalName, String contentType,
                            long sizeBytes, InputStream content, String sha256) {
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

            var request = PutObjectRequest.builder()
                            .bucket(properties.bucket())
                            .key(key)
                            .contentType(normalisedType)
                            // Supplying the length lets the SDK stream rather than buffer the whole
                            // object in heap - the difference between a 50 MB upload and an OOM.
                            .contentLength(sizeBytes);
            // One or the other, never both: with the algorithm set as well the SDK adds its own trailing
            // checksum to the chunked body next to the declared header, and the store refuses even a
            // correct digest ("x-amz-checksum header does not match"), as MinioChecksumAndLockIntegrationTest
            // shows.
            if (sha256 != null) {
                request.checksumSHA256(Checksums.hexToBase64(sha256));
            } else {
                request.checksumAlgorithm(ChecksumAlgorithm.SHA256);
            }
            if (category == FileCategory.PRODUCT_IMAGE) {
                // Keys are immutable UUID paths. CloudFront may cache them without invalidation.
                request.cacheControl(IMMUTABLE_PUBLIC);
            }
            s3.putObject(request.build(),
                    RequestBody.fromInputStream(sniffable, sizeBytes));

            log.debug("Stored {} ({} bytes) as {}", LogSafe.text(originalName), sizeBytes, key);
            return new StoredFile(key, originalName, normalisedType, sizeBytes, clock.instant());

        } catch (IOException ex) {
            throw new StorageException("Could not read the upload stream", ex);
        } catch (SdkException ex) {
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
        } catch (SdkException ex) {
            throw new StorageException("Object store could not serve " + key, ex);
        }
    }

    @Override
    public ObjectStat stat(String key) {
        try {
            var head = s3.headObject(HeadObjectRequest.builder()
                    .bucket(properties.bucket())
                    .key(StorageKeys.requireValid(key))
                    .checksumMode(ChecksumMode.ENABLED)
                    .build());
            return new ObjectStat(head.contentLength() == null ? 0 : head.contentLength(), head.contentType(),
                    Checksums.base64ToHexOrNull(head.checksumSHA256()));
        } catch (NoSuchKeyException absent) {
            throw new StorageException("No stored file with key " + key, absent);
        } catch (SdkException ex) {
            throw new StorageException("Object store could not describe " + key, ex);
        }
    }

    @Override
    public void retain(String key, Instant until) {
        if (until == null || !until.isAfter(clock.instant())) {
            throw new IllegalArgumentException("A retention must end in the future");
        }
        try {
            s3.putObjectRetention(PutObjectRetentionRequest.builder()
                    .bucket(properties.bucket())
                    .key(StorageKeys.requireValid(key))
                    .retention(ObjectLockRetention.builder()
                            .mode(objectLock.mode() == ObjectLockProperties.Mode.COMPLIANCE
                                    ? ObjectLockRetentionMode.COMPLIANCE : ObjectLockRetentionMode.GOVERNANCE)
                            .retainUntilDate(until)
                            .build())
                    .build());
        } catch (NoSuchKeyException absent) {
            throw new StorageException("No stored file with key " + key, absent);
        } catch (SdkException ex) {
            throw new StorageException("Object store could not lock " + key, ex);
        }
    }

    /** Credentials and addressing match the upload client; the bucket remains private. */
    @Override
    public String presignedDownloadUrl(String key, Duration validFor) {
        if (validFor == null || validFor.isNegative() || validFor.isZero()
                || validFor.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("Download URL duration must be positive and at most one hour");
        }
        try {
            return presigner.presignGetObject(GetObjectPresignRequest.builder()
                    .signatureDuration(validFor)
                    .getObjectRequest(GetObjectRequest.builder().bucket(properties.bucket())
                            .key(StorageKeys.requireValid(key)).build())
                    .build()).url().toExternalForm();
        } catch (SdkException ex) {
            throw new StorageException("Could not sign download URL", ex);
        }
    }

    /**
     * Server-side copy. {@code REPLACE} because the default {@code COPY} directive would carry the
     * source's headers over, and a published rendition needs the long immutable cache header its
     * private source deliberately does not have. SHA-256 is asked for again, so the copy carries the
     * digest even when the source was written without one.
     */
    @Override
    public void copy(String sourceKey, String targetKey) {
        StorageKeys.requireValid(sourceKey);
        StorageKeys.requireValid(targetKey);
        try {
            var source = s3.headObject(HeadObjectRequest.builder()
                    .bucket(properties.bucket()).key(sourceKey).build());
            var request = CopyObjectRequest.builder()
                    .sourceBucket(properties.bucket()).sourceKey(sourceKey)
                    .destinationBucket(properties.bucket()).destinationKey(targetKey)
                    .metadataDirective(MetadataDirective.REPLACE)
                    .contentType(source.contentType())
                    .checksumAlgorithm(ChecksumAlgorithm.SHA256);
            if (StorageKeys.categoryOf(targetKey) == FileCategory.PRODUCT_IMAGE) {
                request.cacheControl(IMMUTABLE_PUBLIC);
            }
            s3.copyObject(request.build());
        } catch (NoSuchKeyException absent) {
            throw new StorageException("No stored file with key " + sourceKey, absent);
        } catch (SdkException ex) {
            throw new StorageException("Object store could not copy " + sourceKey, ex);
        }
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
        } catch (SdkException ex) {
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
        } catch (SdkException ex) {
            throw new StorageException("Object store could not be reached for " + key, ex);
        }
    }
}
