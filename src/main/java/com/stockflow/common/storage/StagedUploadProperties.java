package com.stockflow.common.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Direct browser uploads ({@link StagedUploads}), bound from {@code stockflow.storage.staged-upload.*}.
 *
 * @param urlTtl      how long the signed PUT is valid: long enough to start a 50 MB upload on a slow
 *                    connection (S3 checks the signature when the request starts, not when it ends),
 *                    short because it is a credential
 * @param sessionTtl  how long an upload may wait to be attached after its slot was given
 * @param perUserPerHour slots one user may take in an hour, so nobody fills the quarantine prefix
 */
@ConfigurationProperties(prefix = "stockflow.storage.staged-upload")
public record StagedUploadProperties(
        @DefaultValue("PT15M") Duration urlTtl,
        @DefaultValue("PT1H") Duration sessionTtl,
        @DefaultValue("120") int perUserPerHour
) {

    public StagedUploadProperties {
        if (urlTtl == null || urlTtl.isNegative() || urlTtl.isZero() || urlTtl.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("stockflow.storage.staged-upload.url-ttl must be positive and at most one hour");
        }
        if (sessionTtl == null || sessionTtl.compareTo(urlTtl) < 0 || sessionTtl.compareTo(Duration.ofHours(24)) > 0) {
            // The quarantine prefix expires after a day (infra/aws lifecycle): a session may not outlive its file.
            throw new IllegalArgumentException(
                    "stockflow.storage.staged-upload.session-ttl must be at least url-ttl and at most a day");
        }
        if (perUserPerHour < 1) {
            throw new IllegalArgumentException("stockflow.storage.staged-upload.per-user-per-hour must be at least 1");
        }
    }
}
