package com.stockflow.common.storage;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FileStorageIntegrationTest {
    @TempDir Path directory;
    private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4e, 0x47, 13, 10, 26, 10, 0, 0, 0, 0};

    private StorageProperties properties() {
        return new StorageProperties("stockflow", "http://localhost:9000", "ap-southeast-1", true,
                directory.toString(), Duration.ofMinutes(10));
    }

    @Test
    void localUploadRoundTripsAndRejectsWrongTypesAndOversize() throws Exception {
        var storage = new LocalFileStorage(properties(), Clock.systemUTC());
        var file = storage.store(FileCategory.PRODUCT_IMAGE, "../../same.png", "image/png",
                PNG.length, new ByteArrayInputStream(PNG));
        assertThat(file.key()).startsWith("product-images/").doesNotContain("..");
        try (var stream = storage.download(file.key())) {
            assertThat(stream.readAllBytes()).isEqualTo(PNG);
        }
        assertThatThrownBy(() -> storage.store(FileCategory.PRODUCT_IMAGE, "x.pdf", "application/pdf",
                12, new ByteArrayInputStream(PNG))).isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.UNSUPPORTED_MEDIA_TYPE));
        assertThatThrownBy(() -> storage.store(FileCategory.PRODUCT_IMAGE, "x.png", "image/png",
                FileCategory.PRODUCT_IMAGE.maxBytes() + 1, new ByteArrayInputStream(PNG)))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE));
        var wrong = Arrays.copyOf(PNG, PNG.length);
        wrong[0] = 0;
        assertThatThrownBy(() -> storage.store(FileCategory.PRODUCT_IMAGE, "x.png", "image/png",
                wrong.length, new ByteArrayInputStream(wrong))).isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.UNSUPPORTED_MEDIA_TYPE));
    }

    @Test
    void rollbackDeletesUploadedObjectButCommitKeepsIt() {
        var storage = new LocalFileStorage(properties(), Clock.systemUTC());
        var transfers = new FileTransfers(storage, properties(), Clock.systemUTC());
        for (int outcome : new int[]{TransactionSynchronization.STATUS_ROLLED_BACK,
                TransactionSynchronization.STATUS_COMMITTED, TransactionSynchronization.STATUS_UNKNOWN}) {
            TransactionSynchronizationManager.setActualTransactionActive(true);
            TransactionSynchronizationManager.initSynchronization();
            try {
                var file = transfers.store(FileCategory.PRODUCT_IMAGE,
                        new FileUpload("x.png", "image/png", PNG.length, new ByteArrayInputStream(PNG)));
                TransactionSynchronizationManager.getSynchronizations()
                        .forEach(callback -> callback.afterCompletion(outcome));
                assertThat(storage.exists(file.key()))
                        .isEqualTo(outcome != TransactionSynchronization.STATUS_ROLLED_BACK);
            } finally {
                TransactionSynchronizationManager.clear();
            }
        }
    }

    @Test
    void localStatReportsTheDigestAndADeclaredDigestIsChecked() {
        var storage = new LocalFileStorage(properties(), Clock.systemUTC());
        var file = storage.store(FileCategory.PRODUCT_IMAGE, "x.png", "image/png", PNG.length,
                new ByteArrayInputStream(PNG), Checksums.sha256Hex(PNG));

        var stat = storage.stat(file.key());

        assertThat(stat.sha256()).isEqualTo(Checksums.sha256Hex(PNG));
        assertThat(stat.sizeBytes()).isEqualTo(PNG.length);
        assertThat(stat.contentType()).isEqualTo("image/png");
        assertThatThrownBy(() -> storage.store(FileCategory.PRODUCT_IMAGE, "y.png", "image/png", PNG.length,
                new ByteArrayInputStream(PNG), Checksums.sha256Hex(new byte[] {1})))
                .isInstanceOf(StorageException.class);
        assertThatThrownBy(() -> storage.stat("product-images/2026/01/01/00000000-0000-7000-8000-000000000000.png"))
                .isInstanceOf(StorageException.class);
    }

    @Test
    void retentionIsAppliedOnlyAfterCommitAndOnlyWhenEnabled() {
        var storage = mock(FileStorage.class);
        var enabled = new ObjectLockProperties(true, ObjectLockProperties.Mode.GOVERNANCE, Duration.ofDays(3),
                Duration.ofDays(10));
        String key = "design-renders/2026/01/01/00000000-0000-7000-8000-000000000001.pdf";
        for (boolean lockOn : new boolean[]{true, false}) {
            var transfers = new FileTransfers(storage, properties(), Clock.systemUTC(),
                    lockOn ? enabled : ObjectLockProperties.disabled());
            for (boolean commit : new boolean[]{true, false}) {
                org.mockito.Mockito.clearInvocations(storage);
                TransactionSynchronizationManager.setActualTransactionActive(true);
                TransactionSynchronizationManager.initSynchronization();
                try {
                    transfers.retainAfterCommit(key, RecordRetention.CONFIRMED_DESIGN);
                    org.mockito.Mockito.verifyNoInteractions(storage);
                    TransactionSynchronizationManager.getSynchronizations().forEach(callback -> {
                        if (commit) { callback.afterCommit(); }
                        callback.afterCompletion(commit ? TransactionSynchronization.STATUS_COMMITTED
                                : TransactionSynchronization.STATUS_ROLLED_BACK);
                    });
                    if (lockOn && commit) {
                        org.mockito.Mockito.verify(storage).retain(org.mockito.ArgumentMatchers.eq(key),
                                org.mockito.ArgumentMatchers.argThat(until -> until.isAfter(
                                        java.time.Instant.now().plus(Duration.ofDays(3)).minusSeconds(60))));
                    } else {
                        org.mockito.Mockito.verifyNoInteractions(storage);
                    }
                } finally {
                    TransactionSynchronizationManager.clear();
                }
            }
        }
    }

    @Test
    void aFailedLockAfterCommitIsLoggedNotThrown() {
        var storage = mock(FileStorage.class);
        org.mockito.Mockito.doThrow(new StorageException("no Object Lock on this bucket"))
                .when(storage).retain(any(), any());
        var transfers = new FileTransfers(storage, properties(), Clock.systemUTC(),
                new ObjectLockProperties(true, ObjectLockProperties.Mode.GOVERNANCE, Duration.ofDays(1), Duration.ofDays(1)));
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        try {
            transfers.retainAfterCommit("documents/2026/01/01/00000000-0000-7000-8000-000000000002.pdf",
                    RecordRetention.SALES_CONTRACT);
            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        } finally {
            TransactionSynchronizationManager.clear();
        }
    }

    @Test
    void retentionOutsideATransactionIsRefused() {
        var transfers = new FileTransfers(mock(FileStorage.class), properties(), Clock.systemUTC());
        assertThatThrownBy(() -> transfers.retainAfterCommit(
                "design-renders/2026/01/01/00000000-0000-7000-8000-000000000003.pdf", RecordRetention.CONFIRMED_DESIGN))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void publishCopiesARenditionToTheCdnPrefixAndRollbackTakesItBack() {
        var storage = new LocalFileStorage(properties(), Clock.systemUTC());
        var transfers = new FileTransfers(storage, properties(), Clock.systemUTC());
        var rendition = storage.store(FileCategory.PRODUCT_RENDITION, "d.png", "image/png",
                PNG.length, new ByteArrayInputStream(PNG));
        String expected = "product-images/" + rendition.key().substring("product-renditions/".length());
        for (int outcome : new int[]{TransactionSynchronization.STATUS_ROLLED_BACK,
                TransactionSynchronization.STATUS_COMMITTED}) {
            TransactionSynchronizationManager.setActualTransactionActive(true);
            TransactionSynchronizationManager.initSynchronization();
            try {
                assertThat(transfers.publish(rendition.key())).isEqualTo(expected);
                assertThat(storage.exists(expected)).isTrue();
                assertThat(storage.exists(rendition.key())).as("the private source stays").isTrue();
                TransactionSynchronizationManager.getSynchronizations()
                        .forEach(callback -> callback.afterCompletion(outcome));
                assertThat(storage.exists(expected)).isEqualTo(outcome == TransactionSynchronization.STATUS_COMMITTED);
            } finally {
                TransactionSynchronizationManager.clear();
            }
        }
    }

    @Test
    void publishNeverDeletesACopyAnEarlierApprovalCreated() {
        var storage = new LocalFileStorage(properties(), Clock.systemUTC());
        var transfers = new FileTransfers(storage, properties(), Clock.systemUTC());
        var rendition = storage.store(FileCategory.PRODUCT_RENDITION, "d.png", "image/png",
                PNG.length, new ByteArrayInputStream(PNG));
        String published = StorageKeys.publishedKeyOf(rendition.key());
        storage.copy(rendition.key(), published);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        try {
            assertThat(transfers.publish(rendition.key())).isEqualTo(published);
            assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
        } finally {
            TransactionSynchronizationManager.clear();
        }
        assertThat(storage.exists(published)).isTrue();
    }

    @Test
    void publishOutsideATransactionIsRefused() {
        var storage = new LocalFileStorage(properties(), Clock.systemUTC());
        var transfers = new FileTransfers(storage, properties(), Clock.systemUTC());
        assertThatThrownBy(() -> transfers.publish("product-renditions/2026/09/18/x.png"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void copyOfAMissingSourceIsStorageError() {
        var storage = new LocalFileStorage(properties(), Clock.systemUTC());
        assertThatThrownBy(() -> storage.copy("product-renditions/2026/09/18/none.png", "product-images/2026/09/18/none.png"))
                .isInstanceOf(StorageException.class);
    }

    @Test
    void publishedKeyMapsOnlyRenditionsAndLeavesLegacyPublicKeysAlone() {
        assertThat(StorageKeys.publishedKeyOf("product-renditions/2026/09/18/a.jpg"))
                .isEqualTo("product-images/2026/09/18/a.jpg");
        assertThat(StorageKeys.publishedKeyOf("product-images/2026/09/18/a.jpg"))
                .isEqualTo("product-images/2026/09/18/a.jpg");
        assertThatThrownBy(() -> StorageKeys.publishedKeyOf("product-originals/2026/09/18/a.png"))
                .isInstanceOf(StorageException.class);
        assertThatThrownBy(() -> StorageKeys.publishedKeyOf("design-renders/2026/09/18/a.png"))
                .isInstanceOf(StorageException.class);
    }

    @Test
    void diskFailureIsStorageError() throws Exception {
        var storage = new LocalFileStorage(properties(), Clock.systemUTC());
        Files.writeString(directory.resolve("product-images"), "Blocks creation of category directory");
        assertThatThrownBy(() -> storage.store(FileCategory.PRODUCT_IMAGE, "x.png", "image/png",
                PNG.length, new ByteArrayInputStream(PNG))).isInstanceOfSatisfying(StorageException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.STORAGE_ERROR));
    }

    @Test
    void browserDownloadNeverReturnsLocalFilesystemUri() {
        var storage = new LocalFileStorage(properties(), Clock.systemUTC());
        var file = storage.store(FileCategory.PRODUCT_IMAGE, "x.png", "image/png",
                PNG.length, new ByteArrayInputStream(PNG));
        var transfers = new FileTransfers(storage, properties(), Clock.systemUTC());
        assertThatThrownBy(() -> transfers.downloadLink(file.key())).isInstanceOf(StorageException.class);
    }

    @Test
    void sdkConnectionFailureIsStorageErrorAndPresignerUsesPrivateSignedUrl() {
        var s3 = mock(S3Client.class);
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(SdkClientException.create("Connection refused"));
        try (var presigner = S3Presigner.builder().endpointOverride(URI.create("http://localhost:9000"))
                .region(Region.AP_SOUTHEAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "testsecret")))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build()).build()) {
            var storage = new S3FileStorage(s3, properties(), Clock.systemUTC(), presigner);
            assertThatThrownBy(() -> storage.store(FileCategory.PRODUCT_IMAGE, "x.png", "image/png",
                    PNG.length, new ByteArrayInputStream(PNG))).isInstanceOfSatisfying(StorageException.class,
                            ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.STORAGE_ERROR));
            String url = storage.presignedDownloadUrl("product-images/test.png", Duration.ofMinutes(10));
            assertThat(url).startsWith("http://localhost:9000/stockflow/product-images/test.png?")
                    .contains("X-Amz-Signature=", "X-Amz-Expires=600");
        }
    }

    /** 2F-4: through the backend too, a design image is measured before it is stored, and a huge one never is. */
    @Test
    void aDesignImageIsMeasuredBeforeItIsStored() throws Exception {
        var storage = new LocalFileStorage(properties(), Clock.systemUTC());
        var transfers = new FileTransfers(storage, properties(), Clock.systemUTC());
        byte[] png = png(120, 80);
        try (var prepared = transfers.prepare(FileCategory.DESIGN_ASSET,
                new FileUpload("logo.png", "image/png", png.length, new ByteArrayInputStream(png)))) {
            assertThat(prepared.single().dimensions()).isEqualTo(new ImageDimensions.Size(120, 80));
        }
        byte[] huge = png(1, 1);
        java.nio.ByteBuffer.wrap(huge, 16, 8).putInt(8000).putInt(5001);
        assertThatThrownBy(() -> transfers.prepare(FileCategory.DESIGN_ASSET,
                new FileUpload("huge.png", "image/png", huge.length, new ByteArrayInputStream(huge))))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE));
        try (var files = java.nio.file.Files.walk(directory)) {
            assertThat(files.filter(java.nio.file.Files::isRegularFile)).as("nothing stored, the first one closed unadopted")
                    .isEmpty();
        }
        try (var prepared = transfers.prepare(FileCategory.PRODUCT_IMAGE,
                new FileUpload("p.png", "image/png", png.length, new ByteArrayInputStream(png)))) {
            assertThat(prepared.single().dimensions()).isNull();
        }
    }

    private static byte[] png(int width, int height) throws Exception {
        var out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(width, height,
                java.awt.image.BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }
}
