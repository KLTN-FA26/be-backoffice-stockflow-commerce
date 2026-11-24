package com.stockflow.common.storage;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.ObjectLockEnabled;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Period;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Demo run 24/11, D-3: a fresh MinIO has no bucket, and the first issued e-invoice failed storing its files until
 * somebody made one by hand. Against a real MinIO: the application makes its bucket at startup when it is missing
 * (with Object Lock when it locks records), leaves an existing one alone, reports rather than makes one where creating
 * is turned off, and says so when its credentials may not - never throwing.
 */
@Testcontainers(disabledWithoutDocker = true)
class StorageBucketInitializerIntegrationTest {

    private static final String ROOT = "stockflow-test";
    private static final String ROOT_SECRET = "stockflow-test-secret";
    private static final String WEAK = "weak-user";
    private static final String WEAK_SECRET = "weak-user-secret";

    @Container static final GenericContainer<?> MINIO = new GenericContainer<>("pgsty/minio:RELEASE.2026-08-04T00-00-00Z")
            .withEnv("MINIO_ROOT_USER", ROOT).withEnv("MINIO_ROOT_PASSWORD", ROOT_SECRET)
            .withCommand("server /data").withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));

    private static URI endpoint;
    private static S3Client root;
    private static S3Client weak;

    @BeforeAll
    static void connect() throws Exception {
        endpoint = URI.create("http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000));
        root = client(ROOT, ROOT_SECRET);
        // A user who may read objects of one bucket and nothing else: no s3:ListBucket, no s3:CreateBucket - the VPS's
        // IAM user with a policy that went wrong.
        exec("mc alias set local http://localhost:9000 " + ROOT + " " + ROOT_SECRET);
        exec("printf '%s' '{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Action\":[\"s3:GetObject\"],"
                + "\"Resource\":[\"arn:aws:s3:::elsewhere/*\"]}]}' > /tmp/read-only.json");
        exec("mc admin policy create local read-elsewhere /tmp/read-only.json");
        exec("mc admin user add local " + WEAK + " " + WEAK_SECRET);
        exec("mc admin policy attach local read-elsewhere --user " + WEAK);
        weak = client(WEAK, WEAK_SECRET);
    }

    @AfterAll
    static void disconnect() {
        root.close();
        weak.close();
    }

    private static void exec(String command) throws Exception {
        var result = MINIO.execInContainer("sh", "-c", command);
        assertThat(result.getExitCode()).as(command + ": " + result.getStderr()).isZero();
    }

    private static S3Client client(String key, String secret) {
        return S3Client.builder().endpointOverride(endpoint).region(Region.AP_SOUTHEAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(key, secret)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build()).build();
    }

    private static StorageProperties bucket(String name) {
        return new StorageProperties(name, endpoint.toString(), "ap-southeast-1", true, ".unused",
                Duration.ofMinutes(5));
    }

    private static ObjectLockProperties locking() {
        var off = ObjectLockProperties.disabled();
        return new ObjectLockProperties(true, off.mode(), off.confirmedDesign(), off.salesContract(), Period.ofYears(10),
                ObjectLockProperties.Mode.GOVERNANCE);
    }

    private static boolean exists(String name) {
        try {
            root.headBucket(b -> b.bucket(name));
            return true;
        } catch (NoSuchBucketException missing) {
            return false;
        }
    }

    @Test
    @DisplayName("D-3: a missing bucket is made at startup and the first file stores into it; started again, the "
            + "existing bucket is left as it is")
    void aMissingBucketIsMadeOnce() {
        assertThat(exists("fresh")).isFalse();
        var initializer = new StorageBucketInitializer(root, bucket("fresh"), ObjectLockProperties.disabled(), true);

        initializer.afterSingletonsInstantiated();

        assertThat(exists("fresh")).as("made at startup").isTrue();
        byte[] bytes = "%PDF-1.7\nTest artifact".getBytes(StandardCharsets.US_ASCII);
        var storage = new S3FileStorage(root, bucket("fresh"), Clock.systemUTC(), null);
        var file = storage.store(FileCategory.DESIGN_RENDER, "render.pdf", "application/pdf", bytes.length,
                new ByteArrayInputStream(bytes));
        assertThat(storage.exists(file.key())).as("the first stored file").isTrue();

        assertThat(initializer.ensureBucket()).as("a second start, or a second instance")
                .isEqualTo(StorageBucketInitializer.Outcome.EXISTS);
        assertThat(storage.exists(file.key())).as("nothing in it touched").isTrue();
    }

    @Test
    @DisplayName("an application that locks records gets a bucket with Object Lock: S3 turns it on only at creation")
    void aLockingApplicationGetsALockingBucket() {
        assertThat(new StorageBucketInitializer(root, bucket("records"), locking(), true).ensureBucket())
                .isEqualTo(StorageBucketInitializer.Outcome.CREATED);

        assertThat(root.getObjectLockConfiguration(b -> b.bucket("records")).objectLockConfiguration()
                .objectLockEnabled()).isEqualTo(ObjectLockEnabled.ENABLED);
    }

    @Test
    @DisplayName("creating turned off (the AWS deployments): a missing bucket is reported, not made")
    void creatingTurnedOffReportsTheMissingBucket() {
        assertThat(new StorageBucketInitializer(root, bucket("infra-owned"), ObjectLockProperties.disabled(), false)
                .ensureBucket()).isEqualTo(StorageBucketInitializer.Outcome.MISSING);
        assertThat(exists("infra-owned")).isFalse();
    }

    @Test
    @DisplayName("credentials that may neither look the bucket up nor create it: a clear NO_ACCESS, nothing thrown, "
            + "on a missing bucket and on an existing one")
    void credentialsWithoutTheRightAreReported() {
        var missing = new StorageBucketInitializer(weak, bucket("not-yours"), ObjectLockProperties.disabled(), true);
        assertThat(missing.ensureBucket()).isEqualTo(StorageBucketInitializer.Outcome.NO_ACCESS);
        assertThat(exists("not-yours")).isFalse();

        root.createBucket(b -> b.bucket("existing"));
        var existing = new StorageBucketInitializer(weak, bucket("existing"), ObjectLockProperties.disabled(), true);
        assertThat(existing.ensureBucket()).isEqualTo(StorageBucketInitializer.Outcome.NO_ACCESS);
        // The startup hook itself never throws: storage being wrong must not crash-loop the application.
        existing.afterSingletonsInstantiated();
    }

    @Test
    @DisplayName("a store nobody answers on: UNAVAILABLE, not an exception")
    void anUnreachableStoreIsReported() {
        var nowhere = new StorageProperties("any", "http://127.0.0.1:1", "ap-southeast-1", true, ".unused",
                Duration.ofMinutes(5));
        try (S3Client offline = S3Client.builder().endpointOverride(URI.create(nowhere.endpoint()))
                .region(Region.AP_SOUTHEAST_1).credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(ROOT, ROOT_SECRET)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build()).build()) {
            assertThat(new StorageBucketInitializer(offline, nowhere, ObjectLockProperties.disabled(), true)
                    .ensureBucket()).isEqualTo(StorageBucketInitializer.Outcome.UNAVAILABLE);
        }
        assertThatThrownBy(() -> root.headBucket(b -> b.bucket("never-made")))
                .isInstanceOf(NoSuchBucketException.class);
    }
}
