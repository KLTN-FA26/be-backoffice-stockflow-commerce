package com.stockflow.common.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Configuration for the file store, bound from {@code stockflow.storage.*}.
 *
 * <p>A record, so the values are immutable once bound and there is no half-configured state.</p>
 *
 * @param bucket        the S3 or MinIO bucket
 * @param endpoint      set for MinIO (e.g. {@code http://localhost:9000}); leave empty for real S3
 * @param region        required by the SDK's signing even when talking to MinIO
 * @param pathStyle     MinIO needs path-style addressing; S3 uses virtual-host style. Getting this
 *                      wrong produces DNS failures that look nothing like a configuration problem.
 * @param localDirectory where {@code LocalFileStorage} writes, for developers with no object store
 * @param presignedUrlTtl how long a download URL stays valid - keep it short, see {@link FileStorage}
 */
@ConfigurationProperties(prefix = "stockflow.storage")
public record StorageProperties(
        @DefaultValue("stockflow") String bucket,
        @DefaultValue("") String endpoint,
        @DefaultValue("ap-southeast-1") String region,
        @DefaultValue("true") boolean pathStyle,
        @DefaultValue("./.local-storage") String localDirectory,
        @DefaultValue("PT10M") Duration presignedUrlTtl
) {

    public StorageProperties {
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalArgumentException("stockflow.storage.bucket must be set");
        }
        if (presignedUrlTtl.compareTo(Duration.ofHours(1)) > 0) {
            // A long-lived signed URL is a credential with no revocation. An hour is already
            // generous; anything more should be a deliberate, reviewed decision, not a default
            // somebody raised to make a test pass.
            throw new IllegalArgumentException(
                    "stockflow.storage.presigned-url-ttl must not exceed one hour, got "
                            + presignedUrlTtl);
        }
    }

    public boolean usesCustomEndpoint() {
        return endpoint != null && !endpoint.isBlank();
    }
}
