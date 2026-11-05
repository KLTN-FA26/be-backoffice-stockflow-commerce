package com.stockflow.common.storage;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
import software.amazon.awssdk.services.s3.model.ObjectLockRetentionMode;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the integrity checks rely on, against a real S3 implementation rather than a mock: the store
 * records SHA-256 on every write and copy, returns it from HEAD, refuses bytes that do not match a
 * declared digest, and locks an object when asked.
 */
@Testcontainers(disabledWithoutDocker = true)
class MinioChecksumAndLockIntegrationTest {

    @Container static final GenericContainer<?> MINIO = new GenericContainer<>("pgsty/minio:RELEASE.2026-08-04T00-00-00Z")
            .withEnv("MINIO_ROOT_USER", "stockflow-test").withEnv("MINIO_ROOT_PASSWORD", "stockflow-test-secret")
            .withCommand("server /data").withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));

    private static S3Client client;
    private static S3Presigner signer;
    private static URI endpoint;

    @BeforeAll
    static void connect() {
        endpoint = URI.create("http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000));
        var credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create("stockflow-test", "stockflow-test-secret"));
        var configuration = S3Configuration.builder().pathStyleAccessEnabled(true).build();
        client = S3Client.builder().endpointOverride(endpoint).region(Region.AP_SOUTHEAST_2)
                .credentialsProvider(credentials).serviceConfiguration(configuration).build();
        signer = S3Presigner.builder().endpointOverride(endpoint).region(Region.AP_SOUTHEAST_2)
                .credentialsProvider(credentials).serviceConfiguration(configuration).build();
        client.createBucket(b -> b.bucket("checksums"));
        client.createBucket(b -> b.bucket("locked").objectLockEnabledForBucket(true));
    }

    @AfterAll
    static void disconnect() {
        client.close();
        signer.close();
    }

    private static S3FileStorage storage(String bucket, ObjectLockProperties lock) {
        var properties = new StorageProperties(bucket, endpoint.toString(), "ap-southeast-2", true, ".unused",
                Duration.ofMinutes(5));
        return new S3FileStorage(client, properties, Clock.systemUTC(), signer, lock);
    }

    private static byte[] pdf(String text) {
        return ("%PDF-1.7\n" + text).getBytes(StandardCharsets.US_ASCII);
    }

    @Test
    void everyWriteRecordsTheSha256OfItsBytes() {
        var storage = storage("checksums", ObjectLockProperties.disabled());
        byte[] bytes = pdf("no digest declared");

        var file = storage.store(FileCategory.DESIGN_RENDER, "a.pdf", "application/pdf", bytes.length,
                new ByteArrayInputStream(bytes));

        var stat = storage.stat(file.key());
        assertThat(stat.sha256()).isEqualTo(Checksums.sha256Hex(bytes));
        assertThat(stat.sizeBytes()).isEqualTo(bytes.length);
        assertThat(stat.contentType()).isEqualTo("application/pdf");
    }

    @Test
    void aDeclaredDigestIsCheckedByTheStore() {
        var storage = storage("checksums", ObjectLockProperties.disabled());
        byte[] bytes = pdf("declared correctly");

        var file = storage.store(FileCategory.DESIGN_RENDER, "b.pdf", "application/pdf", bytes.length,
                new ByteArrayInputStream(bytes), Checksums.sha256Hex(bytes));

        assertThat(storage.stat(file.key()).sha256()).isEqualTo(Checksums.sha256Hex(bytes));
    }

    @Test
    void bytesThatDoNotMatchTheDeclaredDigestAreRefused() {
        var storage = storage("checksums", ObjectLockProperties.disabled());
        byte[] bytes = pdf("what arrived");
        String declared = Checksums.sha256Hex(pdf("what was hashed"));

        assertThatThrownBy(() -> storage.store(FileCategory.DESIGN_RENDER, "c.pdf", "application/pdf", bytes.length,
                new ByteArrayInputStream(bytes), declared)).isInstanceOf(StorageException.class);
    }

    @Test
    void aCopyCarriesTheDigest() {
        var storage = storage("checksums", ObjectLockProperties.disabled());
        byte[] bytes = new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3, 4, 5, 6, 7, 8, 9};
        var file = storage.store(FileCategory.PRODUCT_RENDITION, "r.jpg", "image/jpeg", bytes.length,
                new ByteArrayInputStream(bytes));
        String published = StorageKeys.publishedKeyOf(file.key());

        storage.copy(file.key(), published);

        assertThat(storage.stat(published).sha256()).isEqualTo(Checksums.sha256Hex(bytes));
    }

    @Test
    void aMissingObjectIsAStorageError() {
        var storage = storage("checksums", ObjectLockProperties.disabled());

        assertThatThrownBy(() -> storage.stat("design-renders/2026/01/01/00000000-0000-7000-8000-000000000000.pdf"))
                .isInstanceOf(StorageException.class);
    }

    @Test
    void retainLocksTheObjectInTheConfiguredMode() {
        var lock = new ObjectLockProperties(true, ObjectLockProperties.Mode.GOVERNANCE, Duration.ofDays(1),
                Duration.ofDays(1));
        var storage = storage("locked", lock);
        byte[] bytes = pdf("a confirmed design");
        var file = storage.store(FileCategory.DESIGN_RENDER, "d.pdf", "application/pdf", bytes.length,
                new ByteArrayInputStream(bytes));
        Instant until = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);

        storage.retain(file.key(), until);

        var retention = client.getObjectRetention(r -> r.bucket("locked").key(file.key())).retention();
        assertThat(retention.mode()).isEqualTo(ObjectLockRetentionMode.GOVERNANCE);
        assertThat(retention.retainUntilDate()).isEqualTo(until);
    }

    @Test
    void retainOnABucketWithoutObjectLockIsAStorageError() {
        var storage = storage("checksums", ObjectLockProperties.disabled());
        byte[] bytes = pdf("no lock here");
        var file = storage.store(FileCategory.DESIGN_RENDER, "e.pdf", "application/pdf", bytes.length,
                new ByteArrayInputStream(bytes));

        assertThatThrownBy(() -> storage.retain(file.key(), Instant.now().plus(1, ChronoUnit.DAYS)))
                .isInstanceOf(StorageException.class);
    }
}
