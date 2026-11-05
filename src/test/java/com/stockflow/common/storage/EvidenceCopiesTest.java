package com.stockflow.common.storage;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Evidence photos get display copies without metadata; the original stays as uploaded. */
class EvidenceCopiesTest {

    @TempDir Path directory;
    private LocalFileStorage storage;
    private FileTransfers transfers;

    private void setUp() {
        var properties = new StorageProperties("stockflow", "http://localhost:9000", "ap-southeast-1", true,
                directory.toString(), Duration.ofMinutes(10));
        storage = new LocalFileStorage(properties, Clock.systemUTC());
        transfers = new FileTransfers(storage, properties, Clock.systemUTC(), ObjectLockProperties.disabled(),
                new UploadInspection("localhost", 1, false), new ImageRenditions());
    }

    @AfterEach
    void clear() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clear();
        }
    }

    /** A JPEG whose metadata carries a comment, as a phone's would carry its position. */
    private static byte[] jpegWithMetadata(int width, int height) throws Exception {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(Color.ORANGE);
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        var writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        var params = writer.getDefaultWriteParam();
        IIOMetadata metadata = writer.getDefaultImageMetadata(
                javax.imageio.ImageTypeSpecifier.createFromRenderedImage(image), params);
        var root = (IIOMetadataNode) metadata.getAsTree("javax_imageio_jpeg_image_1.0");
        var sequence = (IIOMetadataNode) root.getElementsByTagName("markerSequence").item(0);
        var comment = new IIOMetadataNode("com");
        comment.setAttribute("comment", "GPS 10.7769,106.7009 secret-location");
        sequence.appendChild(comment);
        metadata.setFromTree("javax_imageio_jpeg_image_1.0", root);
        var bytes = new ByteArrayOutputStream();
        try (var output = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(output);
            writer.write(null, new IIOImage(image, null, metadata), params);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    private static byte[] transparentPng() throws Exception {
        var image = new BufferedImage(40, 20, BufferedImage.TYPE_INT_ARGB);
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        return bytes.toByteArray();
    }

    @Test
    void displayCopiesCarryNoneOfTheOriginalsMetadata() throws Exception {
        byte[] photo = jpegWithMetadata(3000, 2000);
        assertThat(new String(photo, StandardCharsets.ISO_8859_1)).contains("secret-location");

        var copies = new ImageRenditions().render(photo, true, 256, 1600);

        assertThat(copies).extracting(ImageRenditions.Rendition::width).containsExactly(256, 1600);
        assertThat(copies).extracting(ImageRenditions.Rendition::contentType).containsOnly("image/jpeg");
        assertThat(copies).allSatisfy(copy ->
                assertThat(new String(copy.bytes(), StandardCharsets.ISO_8859_1)).doesNotContain("secret-location"));
    }

    @Test
    void flatteningPaintsTransparencyAndWritesJpeg() throws Exception {
        var copies = new ImageRenditions().render(transparentPng(), true, 256);
        assertThat(copies.get(0).contentType()).isEqualTo("image/jpeg");
        var decoded = ImageIO.read(new ByteArrayInputStream(copies.get(0).bytes()));
        assertThat(decoded.getRGB(0, 0) & 0xFFFFFF).as("white, not black").isEqualTo(0xFFFFFF);
        assertThat(new ImageRenditions().render(transparentPng(), false, 256).get(0).contentType()).isEqualTo("image/png");
    }

    @Test
    void bytesNoReaderDecodesAreRefused() {
        byte[] fake = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0, 1, 2, 3};
        assertThatThrownBy(() -> new ImageRenditions().render(fake, true, 256))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    void anEvidencePhotoIsStoredWithItsCopiesAndARollbackTakesThemAllBack() throws Exception {
        setUp();
        byte[] photo = jpegWithMetadata(800, 600);
        String original;
        List<String> copies;
        try (var prepared = transfers.prepare(FileCategory.RETURN_EVIDENCE,
                new FileUpload("crack.jpg", "image/jpeg", photo.length, new ByteArrayInputStream(photo)))) {
            var file = prepared.single();
            original = file.file().key();
            copies = file.derivedKeys();
            assertThat(copies).containsExactly(StorageKeys.evidenceCopyOf(original, 256),
                    StorageKeys.evidenceCopyOf(original, 1600));
            assertThat(copies).allSatisfy(key -> {
                assertThat(key).startsWith("evidence-copies/return-evidence/");
                assertThat(storage.exists(key)).isTrue();
            });
            try (var stored = storage.download(original)) {
                assertThat(stored.readAllBytes()).as("the original is kept as uploaded").isEqualTo(photo);
            }
            TransactionSynchronizationManager.setActualTransactionActive(true);
            TransactionSynchronizationManager.initSynchronization();
            prepared.adopt();
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(callback -> callback.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
            TransactionSynchronizationManager.clear();
        }
        assertThat(storage.exists(original)).isFalse();
        assertThat(copies).allSatisfy(key -> assertThat(storage.exists(key)).isFalse());
    }

    @Test
    void aPhotoThatCannotBeRenderedIsStillKeptWithoutCopies() {
        setUp();
        byte[] headerOnly = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0, 0, 0, 0, 0, 0, 1};
        try (var prepared = transfers.prepare(FileCategory.QC_EVIDENCE,
                new FileUpload("x.jpg", "image/jpeg", headerOnly.length, new ByteArrayInputStream(headerOnly)))) {
            assertThat(prepared.single().derivedKeys()).isEmpty();
            assertThat(storage.exists(prepared.single().file().key())).isTrue();
        }
    }

    @Test
    void busyDecodersDoNotTurnAwayEvidence() throws Exception {
        var properties = new StorageProperties("stockflow", "http://localhost:9000", "ap-southeast-1", true,
                directory.toString(), Duration.ofMinutes(10));
        storage = new LocalFileStorage(properties, Clock.systemUTC());
        var busy = org.mockito.Mockito.mock(ImageRenditions.class);
        org.mockito.Mockito.when(busy.render(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.any(int[].class))).thenThrow(new BusinessException(ErrorCode.RATE_LIMITED));
        transfers = new FileTransfers(storage, properties, Clock.systemUTC(), ObjectLockProperties.disabled(),
                new UploadInspection("localhost", 1, false), busy);
        byte[] photo = jpegWithMetadata(300, 200);

        try (var prepared = transfers.prepare(FileCategory.QC_EVIDENCE,
                new FileUpload("busy.jpg", "image/jpeg", photo.length, new ByteArrayInputStream(photo)))) {
            assertThat(prepared.single().derivedKeys()).isEmpty();
            assertThat(storage.exists(prepared.single().file().key())).isTrue();
        }
    }

    @Test
    void otherCategoriesGetNoCopies() throws Exception {
        setUp();
        byte[] photo = jpegWithMetadata(300, 200);
        try (var prepared = transfers.prepare(FileCategory.SUPPLIER_INVOICE,
                new FileUpload("hd.jpg", "image/jpeg", photo.length, new ByteArrayInputStream(photo)))) {
            assertThat(prepared.single().derivedKeys()).isEmpty();
        }
    }

    @Test
    void copyKeysDeriveFromTheOriginalAndOnlyEvidenceHasThem() {
        assertThat(StorageKeys.evidenceCopyOf("qc-evidence/2026/11/06/0192f4c1-8e3a-7b21-9c44-3f2a1b8d5e60.png", 256))
                .isEqualTo("evidence-copies/qc-evidence/2026/11/06/0192f4c1-8e3a-7b21-9c44-3f2a1b8d5e60-256.jpg");
        assertThat(StorageKeys.requireValid("evidence-copies/qc-evidence/2026/11/06/x-256.jpg")).isNotBlank();
        assertThatThrownBy(() -> StorageKeys.evidenceCopyOf("design-renders/2026/11/06/x.pdf", 256))
                .isInstanceOf(StorageException.class);
        assertThatThrownBy(() -> new LocalFileStorage(new StorageProperties("stockflow", "", "ap-southeast-1", true,
                directory.toString(), Duration.ofMinutes(1)), Clock.systemUTC())
                .storeDerived("qc-evidence/2026/11/06/x.jpg", "image/jpeg", new byte[] {1}))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
