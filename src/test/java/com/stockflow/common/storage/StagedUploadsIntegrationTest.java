package com.stockflow.common.storage;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.lock.DistributedLock;
import com.stockflow.common.ratelimit.RateLimiter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A browser upload end to end against a real S3 implementation: the slot, the signed PUT a browser would send, the
 * checks before the file is attached, and the attach itself. Redis, the lock and the rate limiter are in-memory
 * stand-ins with the same contract.
 */
@Testcontainers(disabledWithoutDocker = true)
class StagedUploadsIntegrationTest {

    @Container static final GenericContainer<?> MINIO = new GenericContainer<>("pgsty/minio:RELEASE.2026-08-04T00-00-00Z")
            .withEnv("MINIO_ROOT_USER", "stockflow-test").withEnv("MINIO_ROOT_PASSWORD", "stockflow-test-secret")
            .withCommand("server /data").withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));

    private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4e, 0x47, 13, 10, 26, 10, 0, 0, 0, 13, 'I', 'H', 'D', 'R'};
    private static final byte[] PDF = "%PDF-1.7\n%%EOF".getBytes(StandardCharsets.US_ASCII);

    private static S3Client client;
    private static S3Presigner signer;
    private static URI endpoint;

    private final Map<String, Map<Object, Object>> hashes = new ConcurrentHashMap<>();
    private final Set<String> held = ConcurrentHashMap.newKeySet();
    private final AtomicInteger slotsTaken = new AtomicInteger();
    private S3FileStorage storage;
    private StagedUploads staged;
    private final UUID owner = UUID.randomUUID();

    @BeforeAll
    static void connect() {
        endpoint = URI.create("http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000));
        var credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create("stockflow-test", "stockflow-test-secret"));
        var configuration = S3Configuration.builder().pathStyleAccessEnabled(true).build();
        client = S3Client.builder().endpointOverride(endpoint).region(Region.AP_SOUTHEAST_2)
                .credentialsProvider(credentials).serviceConfiguration(configuration).build();
        signer = S3Presigner.builder().endpointOverride(endpoint).region(Region.AP_SOUTHEAST_2)
                .credentialsProvider(credentials).serviceConfiguration(configuration).build();
        client.createBucket(b -> b.bucket("staged"));
    }

    @AfterAll
    static void disconnect() {
        client.close();
        signer.close();
    }

    @BeforeEach
    void setUp() {
        storage = new S3FileStorage(client, new StorageProperties("staged", endpoint.toString(), "ap-southeast-2", true,
                ".unused", Duration.ofMinutes(5)), Clock.systemUTC(), signer);
        DistributedLock locks = (name, lease) -> held.add(name)
                ? Optional.of(() -> held.remove(name)) : Optional.empty();
        RateLimiter limiter = (key, capacity, window) -> slotsTaken.incrementAndGet() <= capacity
                ? RateLimiter.Decision.allowed(capacity - slotsTaken.get())
                : new RateLimiter.Decision(false, 0, Duration.ofMinutes(1));
        staged = new StagedUploads(storage, new StagedUploadProperties(Duration.ofMinutes(15), Duration.ofHours(1), 3),
                new UploadInspection("localhost", 1, false), redis(), locks, limiter, Clock.systemUTC());
    }

    @AfterEach
    void clear() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clear();
        }
    }

    @SuppressWarnings("unchecked")
    private StringRedisTemplate redis() {
        var redis = mock(StringRedisTemplate.class);
        HashOperations<String, Object, Object> hash = mock(HashOperations.class);
        when(redis.<Object, Object>opsForHash()).thenReturn(hash);
        doAnswer(call -> {
            hashes.computeIfAbsent(call.getArgument(0), key -> new ConcurrentHashMap<>()).putAll(call.getArgument(1));
            return null;
        }).when(hash).putAll(anyString(), anyMap());
        doAnswer(call -> {
            hashes.computeIfAbsent(call.getArgument(0), key -> new ConcurrentHashMap<>())
                    .put(call.getArgument(1), call.getArgument(2));
            return null;
        }).when(hash).put(anyString(), any(), any());
        when(hash.entries(anyString())).thenAnswer(call -> new HashMap<>(hashes.getOrDefault(call.getArgument(0), Map.of())));
        return redis;
    }

    private UploadSlot slot(String name, String type, byte[] bytes) {
        return staged.begin(FileCategory.QC_EVIDENCE, owner,
                new UploadSlotRequest(name, type, bytes.length, Checksums.sha256Hex(bytes)));
    }

    /** What a browser does with a slot: PUT the bytes with exactly the signed headers. */
    private static int put(UploadSlot slot, byte[] body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(slot.url())).PUT(HttpRequest.BodyPublishers.ofByteArray(body));
        // Content-Length is set by the client from the body, as a browser does; Java refuses it as a header.
        slot.headers().forEach((name, value) -> {
            if (!name.equalsIgnoreCase("content-length")) {
                request.header(name, value);
            }
        });
        try (var browser = HttpClient.newHttpClient()) {
            return browser.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
        }
    }

    private static void inTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
    }

    private static void commit() {
        TransactionSynchronizationManager.getSynchronizations().forEach(callback -> {
            callback.afterCommit();
            callback.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
        });
        TransactionSynchronizationManager.clear();
    }

    @Test
    void anUploadedFileIsCheckedMovedIntoItsCategoryAndRememberedOnceAttached() throws Exception {
        UploadSlot slot = slot("crack.png", "image/png", PNG);
        assertThat(slot.method()).isEqualTo("PUT");
        assertThat(put(slot, PNG)).isEqualTo(200);

        String incoming;
        String key;
        try (PreparedFiles prepared = staged.accept(FileCategory.QC_EVIDENCE, owner, List.of(slot.uploadId()))) {
            var file = prepared.single();
            key = file.file().key();
            assertThat(key).startsWith("qc-evidence/").endsWith(slot.uploadId() + ".png");
            assertThat(file.file().originalName()).isEqualTo("crack.png");
            assertThat(file.sha256()).isEqualTo(Checksums.sha256Hex(PNG));
            assertThat(storage.stat(key).sha256()).isEqualTo(Checksums.sha256Hex(PNG));
            incoming = (String) hashes.get("upload:session:" + slot.uploadId()).get("incoming");
            assertThat(storage.exists(incoming)).as("kept until the attach commits, for a retry").isTrue();
            inTransaction();
            prepared.adopt();
            commit();
        }

        assertThat(storage.exists(key)).isTrue();
        assertThat(storage.exists(incoming)).as("the quarantined copy goes once attached").isFalse();
        assertThat(staged.attachedKey(FileCategory.QC_EVIDENCE, owner, slot.uploadId())).contains(key);
        assertThat(held).as("the claim is released").isEmpty();
        assertThatThrownBy(() -> staged.accept(FileCategory.QC_EVIDENCE, owner, List.of(slot.uploadId())))
                .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.CONFLICT));
    }

    @Test
    void anUploadedEvidencePhotoGetsItsDisplayCopiesWhichGoWithIt() throws Exception {
        var image = new java.awt.image.BufferedImage(1200, 900, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var encoded = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "jpeg", encoded);
        byte[] jpeg = encoded.toByteArray();
        UploadSlot slot = slot("parcel.jpg", "image/jpeg", jpeg);
        assertThat(put(slot, jpeg)).isEqualTo(200);

        List<String> copies;
        try (PreparedFiles prepared = staged.accept(FileCategory.QC_EVIDENCE, owner, List.of(slot.uploadId()))) {
            copies = prepared.single().derivedKeys();
            assertThat(copies).hasSize(2).allSatisfy(key -> {
                assertThat(key).startsWith("evidence-copies/qc-evidence/");
                assertThat(storage.exists(key)).isTrue();
            });
        }
        assertThat(copies).as("deleted with the file nobody attached")
                .allSatisfy(key -> assertThat(storage.exists(key)).isFalse());
    }

    @Test
    void aFileNoTransactionAttachedIsRemovedFromItsCategoryAndCanBeAttachedAgain() throws Exception {
        UploadSlot slot = slot("crack.png", "image/png", PNG);
        put(slot, PNG);
        String key;
        try (PreparedFiles prepared = staged.accept(FileCategory.QC_EVIDENCE, owner, List.of(slot.uploadId()))) {
            key = prepared.single().file().key();
        }
        assertThat(storage.exists(key)).isFalse();
        assertThat(held).isEmpty();

        try (PreparedFiles again = staged.accept(FileCategory.QC_EVIDENCE, owner, List.of(slot.uploadId()))) {
            assertThat(again.single().file().key()).as("the same upload lands on the same key").isEqualTo(key);
        }
    }

    @Test
    void theStoreRefusesBytesOtherThanTheDeclaredOnes() throws Exception {
        UploadSlot slot = slot("crack.png", "image/png", PNG);
        byte[] other = PNG.clone();
        other[other.length - 1] = 'X';

        assertThat(put(slot, other)).isBetween(400, 499);
        assertThatThrownBy(() -> staged.accept(FileCategory.QC_EVIDENCE, owner, List.of(slot.uploadId())))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    void bytesOfAnotherTypeThanDeclaredAreRefusedAndNothingIsMoved() throws Exception {
        UploadSlot slot = slot("crack.png", "image/png", PDF);   // the declared digest is the PDF's own
        assertThat(put(slot, PDF)).isEqualTo(200);

        assertThatThrownBy(() -> staged.accept(FileCategory.QC_EVIDENCE, owner, List.of(slot.uploadId())))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.UNSUPPORTED_MEDIA_TYPE));
        assertThat(client.listObjectsV2(b -> b.bucket("staged").prefix("qc-evidence/")).contents())
                .noneMatch(object -> object.key().contains(slot.uploadId().toString()));
        assertThat(held).isEmpty();
    }

    @Test
    void anUploadThatNeverArrivedIsNotAccepted() {
        UploadSlot slot = slot("crack.png", "image/png", PNG);

        assertThatThrownBy(() -> staged.accept(FileCategory.QC_EVIDENCE, owner, List.of(slot.uploadId())))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    void someoneElsesUploadOrAnotherCategoryIsNotFound() throws Exception {
        UploadSlot slot = slot("crack.png", "image/png", PNG);
        put(slot, PNG);

        assertThatThrownBy(() -> staged.accept(FileCategory.QC_EVIDENCE, UUID.randomUUID(), List.of(slot.uploadId())))
                .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> staged.accept(FileCategory.RETURN_EVIDENCE, owner, List.of(slot.uploadId())))
                .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        assertThat(staged.attachedKey(FileCategory.QC_EVIDENCE, UUID.randomUUID(), slot.uploadId())).isEmpty();
    }

    @Test
    void anUploadBeingAttachedIsNotAttachedASecondTimeAtOnce() throws Exception {
        UploadSlot slot = slot("crack.png", "image/png", PNG);
        put(slot, PNG);

        try (PreparedFiles first = staged.accept(FileCategory.QC_EVIDENCE, owner, List.of(slot.uploadId()))) {
            assertThat(first.files()).hasSize(1);
            assertThatThrownBy(() -> staged.accept(FileCategory.QC_EVIDENCE, owner, List.of(slot.uploadId())))
                    .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        }
    }

    @Test
    void severalUploadsAreAcceptedTogetherOrNotAtAll() throws Exception {
        UploadSlot good = slot("a.png", "image/png", PNG);
        put(good, PNG);
        UploadSlot missing = slot("b.png", "image/png", PNG);

        assertThatThrownBy(() -> staged.accept(FileCategory.QC_EVIDENCE, owner, List.of(good.uploadId(), missing.uploadId())))
                .isInstanceOf(BusinessException.class);
        assertThat(client.listObjectsV2(b -> b.bucket("staged").prefix("qc-evidence/")).contents())
                .noneMatch(object -> object.key().contains(good.uploadId().toString()));
        assertThat(held).isEmpty();
        assertThatThrownBy(() -> staged.accept(FileCategory.QC_EVIDENCE, owner, List.of(good.uploadId(), good.uploadId())))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    void theDeclarationIsCheckedBeforeASlotIsGivenAndSlotsAreBudgeted() {
        assertThatThrownBy(() -> slot("x.pdf", "application/pdf", PDF))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.UNSUPPORTED_MEDIA_TYPE));
        assertThatThrownBy(() -> staged.begin(FileCategory.QC_EVIDENCE, owner, new UploadSlotRequest("big.png",
                "image/png", FileCategory.QC_EVIDENCE.maxBytes() + 1, Checksums.sha256Hex(PNG))))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE));
        slot("1.png", "image/png", PNG);
        slot("2.png", "image/png", PNG);
        slot("3.png", "image/png", PNG);
        assertThatThrownBy(() -> slot("4.png", "image/png", PNG))
                .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.RATE_LIMITED));
    }

    @Test
    void onlyTheQuarantinePrefixIsEverSignedForABrowserWrite() {
        assertThatThrownBy(() -> storage.presignedUploadUrl("qc-evidence/2026/01/01/x.png", "image/png", 1,
                Checksums.sha256Hex(PNG), Duration.ofMinutes(1))).isInstanceOf(IllegalArgumentException.class);
        assertThat(StorageKeys.requireValid("incoming/qc-evidence/2026/01/01/" + UUID.randomUUID())).startsWith("incoming/");
        assertThatThrownBy(() -> StorageKeys.requireValid("incoming/../etc/passwd")).isInstanceOf(StorageException.class);
        assertThatThrownBy(() -> StorageKeys.requireValid("incoming/nowhere/x")).isInstanceOf(StorageException.class);
    }
}
