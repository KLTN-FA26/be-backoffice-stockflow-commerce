package com.stockflow.common.storage;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Uploads prepared with no transaction, then adopted by a short one - or deleted. */
class PreparedFilesTest {

    @TempDir Path directory;
    private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4e, 0x47, 13, 10, 26, 10, 0, 0, 0, 1};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0, 0, 0, 0, 0, 0, 2};

    private LocalFileStorage storage;
    private FileTransfers transfers;

    private void setUp(UploadInspection inspection) {
        var properties = new StorageProperties("stockflow", "http://localhost:9000", "ap-southeast-1", true,
                directory.toString(), Duration.ofMinutes(10));
        storage = new LocalFileStorage(properties, Clock.systemUTC());
        transfers = new FileTransfers(storage, properties, Clock.systemUTC(), ObjectLockProperties.disabled(), inspection);
    }

    private void setUp() {
        setUp(new UploadInspection("localhost", 1, false));
    }

    @AfterEach
    void clear() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clear();
        }
    }

    private static FileUpload png(String name) {
        return new FileUpload(name, "image/png", PNG.length, new ByteArrayInputStream(PNG));
    }

    private static FileUpload jpeg(String name) {
        return new FileUpload(name, "image/jpeg", JPEG.length, new ByteArrayInputStream(JPEG));
    }

    private static void inTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
    }

    private static void complete(int status) {
        TransactionSynchronizationManager.getSynchronizations().forEach(callback -> {
            if (status == TransactionSynchronization.STATUS_COMMITTED) { callback.afterCommit(); }
            callback.afterCompletion(status);
        });
        TransactionSynchronizationManager.clear();
    }

    @Test
    void severalUploadsAreStoredInOrderWithTheirDigests() {
        setUp();
        try (var prepared = transfers.prepare(FileCategory.QC_EVIDENCE, List.of(png("a.png"), jpeg("b.jpg"), png("c.png")))) {
            assertThat(prepared.files()).extracting(file -> file.file().originalName())
                    .containsExactly("a.png", "b.jpg", "c.png");
            assertThat(prepared.files().get(1).sha256()).isEqualTo(Checksums.sha256Hex(JPEG));
            assertThat(prepared.files()).allSatisfy(file -> assertThat(storage.exists(file.file().key())).isTrue());
            inTransaction();
            prepared.adopt();
            complete(TransactionSynchronization.STATUS_COMMITTED);
            prepared.files().forEach(file -> assertThat(storage.exists(file.file().key())).isTrue());
        }
    }

    @Test
    void aFileNoTransactionAdoptedIsDeletedOnClose() {
        setUp();
        String key;
        try (var prepared = transfers.prepare(FileCategory.QC_EVIDENCE, png("a.png"))) {
            key = prepared.single().file().key();
        }
        assertThat(storage.exists(key)).isFalse();
    }

    @Test
    void anAdoptingTransactionThatRollsBackDeletesTheFiles() {
        setUp();
        String key;
        try (var prepared = transfers.prepare(FileCategory.QC_EVIDENCE, png("a.png"))) {
            key = prepared.single().file().key();
            inTransaction();
            prepared.adopt();
            prepared.adopt();   // a retried callback registers the cleanup once
            assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);
            complete(TransactionSynchronization.STATUS_ROLLED_BACK);
        }
        assertThat(storage.exists(key)).isFalse();
    }

    @Test
    void anUnknownCommitOutcomeKeepsTheFiles() {
        setUp();
        String key;
        try (var prepared = transfers.prepare(FileCategory.QC_EVIDENCE, png("a.png"))) {
            key = prepared.single().file().key();
            inTransaction();
            prepared.adopt();
            complete(TransactionSynchronization.STATUS_UNKNOWN);
        }
        assertThat(storage.exists(key)).as("a row may reference it").isTrue();
    }

    @Test
    void adoptingOutsideATransactionIsRefused() {
        setUp();
        try (var prepared = transfers.prepare(FileCategory.QC_EVIDENCE, png("a.png"))) {
            assertThatThrownBy(prepared::adopt).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void oneRefusedUploadLeavesNoneOfTheOthersBehind() {
        setUp();
        var notAnImage = new FileUpload("x.pdf", "application/pdf", 9, new ByteArrayInputStream("%PDF-1.7\n".getBytes()));

        assertThatThrownBy(() -> transfers.prepare(FileCategory.QC_EVIDENCE, List.of(png("a.png"), notAnImage, png("c.png"))))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.UNSUPPORTED_MEDIA_TYPE));

        try (var files = java.nio.file.Files.walk(directory)) {
            assertThat(files.filter(java.nio.file.Files::isRegularFile)).as("both stored photos deleted again").isEmpty();
        } catch (java.io.IOException ex) {
            throw new AssertionError(ex);
        }
    }

    @Test
    void aFileTheScannerRefusesIsNeverStored() throws Exception {
        try (var server = new java.net.ServerSocket(0)) {
            var peer = java.util.concurrent.CompletableFuture.runAsync(() -> {
                try (var socket = server.accept()) {
                    var input = new java.io.DataInputStream(socket.getInputStream());
                    input.readNBytes(10);
                    for (int size; (size = input.readInt()) != 0;) { input.readNBytes(size); }
                    socket.getOutputStream().write("stream: Eicar-Signature FOUND\0".getBytes());
                } catch (java.io.IOException ex) { throw new RuntimeException(ex); }
            });
            setUp(new UploadInspection("localhost", server.getLocalPort(), true));

            assertThatThrownBy(() -> transfers.prepare(FileCategory.QC_EVIDENCE, png("a.png")))
                    .isInstanceOf(BusinessException.class);
            peer.get(5, java.util.concurrent.TimeUnit.SECONDS);
        }
        try (var files = java.nio.file.Files.walk(directory)) {
            assertThat(files.filter(java.nio.file.Files::isRegularFile)).isEmpty();
        }
    }

    @Test
    void anAttachmentLinkCarriesTheOriginalNameEncoded() {
        var disposition = DownloadDisposition.attachment("Hoá đơn\u0007 01.pdf");

        assertThat(disposition.contentDisposition()).startsWith("attachment;")
                .contains("filename*=UTF-8''Ho%C3%A1%20%C4%91%C6%A1n_%2001.pdf")
                .doesNotContain("\u0007");
        assertThat(disposition.cacheControl()).isEqualTo("private, no-store");
        assertThat(DownloadDisposition.attachment(" ").contentDisposition()).isEqualTo("attachment");
    }

    @Test
    void aViewLinkMayBeCachedForAsLongAsItLives() {
        var disposition = DownloadDisposition.inline(Duration.ofMinutes(5));

        assertThat(disposition.contentDisposition()).isEqualTo("inline");
        assertThat(disposition.cacheControl()).isEqualTo("private, max-age=300");
    }
}
