package com.stockflow.common.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketAlreadyExistsException;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Makes sure the bucket the application stores into exists before anything stores into it.
 *
 * <h2>Why the application does this</h2>
 *
 * <p>A fresh MinIO (a new laptop, {@code docker compose down -v}, a CI runner) has no bucket at all, and nothing else
 * made one: the first issued e-invoice then failed on {@code NoSuchBucketException} while storing its XML and PDF and
 * sat in SUBMITTING, already numbered by the provider, until somebody created the bucket by hand (demo run 24/11,
 * D-3). So at startup the bucket is looked up and, when it is missing and {@code stockflow.storage.create-missing-bucket}
 * allows it, created - with Object Lock when the application locks records, because S3 only turns Object Lock on when
 * a bucket is created.</p>
 *
 * <h2>Why it never stops the application</h2>
 *
 * <p>Every outcome is logged and none throws: a storage outage at boot would otherwise crash-loop an application
 * whose other work does not need storage, the reasoning {@link ObjectStorageHealthIndicator} keeps storage out of
 * readiness for. What it does instead is say exactly what is wrong, once, at ERROR - the bucket's name and the right
 * the credentials lack - where the old failure surfaced minutes later as a stuck invoice.</p>
 *
 * <h2>On AWS (the VPS, production)</h2>
 *
 * <p>The bucket comes from {@code infra/aws} with its policy, CORS and encryption; a bucket the application made would
 * have none of them, so the deployed profiles turn creation off and a missing bucket is reported, not made. The IAM
 * user there holds {@code s3:ListBucket} (deploy/README), so the lookup - a {@code HEAD} on the bucket - succeeds on
 * an existing bucket and nothing else happens. Without that right the lookup answers 403, reported as such.</p>
 */
@Component
@ConditionalOnProperty(prefix = "stockflow.storage", name = "provider", havingValue = "s3", matchIfMissing = true)
class StorageBucketInitializer implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(StorageBucketInitializer.class);

    /** What the startup check found, and did. */
    enum Outcome {
        /** The bucket was there. */
        EXISTS,
        /** It was missing and was made. */
        CREATED,
        /** It is missing and creating it is turned off. */
        MISSING,
        /** The credentials may not look the bucket up, or may not create it. */
        NO_ACCESS,
        /** The name belongs to somebody else's bucket (S3 bucket names are global). */
        NAME_TAKEN,
        /** No answer: the store is unreachable, or answered something else. */
        UNAVAILABLE
    }

    private final S3Client s3;
    private final StorageProperties properties;
    private final ObjectLockProperties objectLock;
    private final boolean createMissing;

    StorageBucketInitializer(S3Client s3, StorageProperties properties, ObjectLockProperties objectLock,
                             @Value("${stockflow.storage.create-missing-bucket:true}") boolean createMissing) {
        this.s3 = s3;
        this.properties = properties;
        this.objectLock = objectLock;
        this.createMissing = createMissing;
    }

    /**
     * Before the context finishes starting, so before any scheduled job or replayed event listener (the e-invoice
     * issuing job among them) stores a file.
     */
    @Override
    public void afterSingletonsInstantiated() {
        ensureBucket();
    }

    Outcome ensureBucket() {
        String bucket = properties.bucket();
        try {
            s3.headBucket(b -> b.bucket(bucket));
            log.debug("Storage bucket '{}' exists", bucket);
            return Outcome.EXISTS;
        } catch (NoSuchBucketException missing) {
            return createMissing ? create(bucket) : reportMissing(bucket);
        } catch (S3Exception refused) {
            if (refused.statusCode() == 404) {
                return createMissing ? create(bucket) : reportMissing(bucket);
            }
            if (refused.statusCode() == 403) {
                log.error("Storage bucket '{}' cannot be checked: the storage credentials are refused (403). They need "
                        + "s3:ListBucket on the bucket, and the bucket must belong to their account. Uploads and stored "
                        + "documents fail until this is fixed.", bucket);
                return Outcome.NO_ACCESS;
            }
            return unavailable(bucket, refused);
        } catch (SdkException unreachable) {
            return unavailable(bucket, unreachable);
        }
    }

    private Outcome create(String bucket) {
        CreateBucketRequest.Builder request = CreateBucketRequest.builder().bucket(bucket)
                // Object Lock can only be turned on when a bucket is made; a bucket made without it can never lock
                // an issued invoice, and issuing refuses to mark an invoice issued when the lock fails.
                .objectLockEnabledForBucket(objectLock.enabled());
        // AWS outside us-east-1 needs the region named; MinIO and other S3-compatible stores take the default.
        if (!properties.usesCustomEndpoint() && !Region.US_EAST_1.id().equals(properties.region())) {
            request.createBucketConfiguration(c -> c.locationConstraint(properties.region()));
        }
        try {
            s3.createBucket(request.build());
            log.info("Storage bucket '{}' did not exist and was created{}", bucket,
                    objectLock.enabled() ? " with Object Lock" : "");
            return Outcome.CREATED;
        } catch (BucketAlreadyOwnedByYouException raced) {
            // Another instance starting at the same moment made it first.
            return Outcome.EXISTS;
        } catch (BucketAlreadyExistsException taken) {
            log.error("Storage bucket '{}' does not exist for these credentials and its name is taken by another "
                    + "account; set stockflow.storage.bucket (S3_BUCKET) to a bucket of this account.", bucket);
            return Outcome.NAME_TAKEN;
        } catch (S3Exception refused) {
            if (refused.statusCode() == 403) {
                log.error("Storage bucket '{}' does not exist and the storage credentials may not create it (403: "
                        + "s3:CreateBucket missing). Create the bucket{} or use credentials that may; uploads and "
                        + "stored documents fail until then.", bucket,
                        objectLock.enabled() ? " with Object Lock" : "");
                return Outcome.NO_ACCESS;
            }
            return unavailable(bucket, refused);
        } catch (SdkException unreachable) {
            return unavailable(bucket, unreachable);
        }
    }

    private Outcome reportMissing(String bucket) {
        log.error("Storage bucket '{}' does not exist, and stockflow.storage.create-missing-bucket is off for this "
                + "deployment: create it (infra/aws for AWS){}. Uploads and stored documents fail until then.", bucket,
                objectLock.enabled() ? " with Object Lock" : "");
        return Outcome.MISSING;
    }

    private Outcome unavailable(String bucket, SdkException failure) {
        // The type and status only: an SDK message can carry the endpoint and a request id (see the health indicator).
        log.error("Storage bucket '{}' could not be checked at startup ({}{}); /actuator/health shows objectStorage "
                        + "until the store answers.", bucket, failure.getClass().getSimpleName(),
                failure instanceof S3Exception s3Failure ? " " + s3Failure.statusCode() : "");
        return Outcome.UNAVAILABLE;
    }
}
