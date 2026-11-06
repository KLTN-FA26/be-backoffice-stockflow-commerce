package com.stockflow.procurement;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.storage.FileCategory;
import com.stockflow.common.storage.FileStorage;
import com.stockflow.common.storage.FileUpload;
import com.stockflow.common.storage.IncomingFiles;
import com.stockflow.common.storage.StagedUploads;
import com.stockflow.common.storage.StorageKeys;
import com.stockflow.common.storage.StoredFileReferences;
import com.stockflow.procurement.internal.domain.GoodsReceiptPhotoStage;
import com.stockflow.procurement.internal.domain.GoodsReceiptStatus;
import com.stockflow.procurement.internal.service.GoodsReceiptPhotoService;
import com.stockflow.procurement.internal.service.GoodsReceiptPhotoService.PhotoView;
import com.stockflow.procurement.internal.service.GoodsReceipts;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.TestUsers;
import com.stockflow.support.WithCurrentUser;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Photos of a goods receipt (04b F-RCV steps 05 and 07) through the real storage path and schema: the warehouse's while
 * the receipt is counted, QC's of a line it inspects while the receipt is in QC.
 *
 * <p>The fixture is the demo supplier's order of {@value #SOFA} (no QC) and {@value #TABLE} (QC, lot and expiry) into
 * HCM. Confirming the count sends the receipt to IN_QC, because the table goes through QC.</p>
 */
@IntegrationTest
@Import(PostgresContainer.class)
@TestPropertySource(properties = "stockflow.upload-inspection.enabled=false")
class GoodsReceiptPhotoIntegrationTest {

    private static final String SOFA = "SOFA-3S-GREY";
    private static final String TABLE = "TABLE-OAK-160";

    @Autowired GoodsReceiptPhotoService photos;
    @Autowired GoodsReceipts receipts;
    @Autowired List<StoredFileReferences> owners;
    // The test profile stores files locally, which signs no browser link; the link only has to look like S3's here.
    @MockitoSpyBean FileStorage storage;
    @MockitoSpyBean StagedUploads staged;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate tx;

    private UUID clerk;
    private UUID inspector;
    private UUID po;
    private UUID receipt;
    private UUID sofaLine;
    private UUID tableLine;

    @BeforeEach
    void setUp() {
        doAnswer(call -> "http://objects.test/" + call.getArgument(0)).when(storage)
                .presignedDownloadUrl(anyString(), any(), any());
        clerk = tx.execute(s -> ReferenceRows.user(entityManager));
        inspector = tx.execute(s -> ReferenceRows.user(entityManager));
        po = order();
        receipt = receipts.create(new GoodsReceipts.Create(po, "DN-PHOTO", null), clerk).id();
        String lot = "PH-" + UUID.randomUUID().toString().substring(0, 8);
        var counted = receipts.replaceLines(receipt, List.of(
                new GoodsReceipts.NewLine(poLine(po, SOFA), 4, null, null, "HCM-RCV01", null),
                new GoodsReceipts.NewLine(poLine(po, TABLE), 6, lot, LocalDate.now().plusYears(1), "HCM-RCV01", null)));
        sofaLine = lineOf(counted, po, SOFA);
        tableLine = lineOf(counted, po, TABLE);
    }

    /** A CONFIRMED order of the demo supplier into HCM: 10 sofas and 10 tables. */
    private UUID order() {
        UUID po = Identifiers.newId();
        UUID revision = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            UUID submitter = ReferenceRows.user(entityManager);
            UUID approver = ReferenceRows.user(entityManager);
            jdbc.update("""
                    INSERT INTO procurement.purchase_orders (id, po_number, status, supplier_id, warehouse_id, currency,
                        order_date, submitted_at, submitted_by, approved_at, approved_by, confirmed_at, confirmed_by,
                        active_revision_id, version, created_at)
                    SELECT ?, ?, 'CONFIRMED', s.id, w.id, 'VND', CURRENT_DATE, NOW(), ?, NOW(), ?, NOW(), ?, ?, 0, NOW()
                      FROM procurement.suppliers s, warehouse.warehouse w WHERE s.code = 'GOVIET' AND w.prefix = 'HCM'""",
                    po, "PO-PH-" + po.toString().substring(24), submitter, approver, approver, revision);
            jdbc.update("""
                    INSERT INTO procurement.purchase_order_revisions (id, po_id, revision_no, kind, snapshot_header,
                        changed_by, version, created_at) VALUES (?, ?, 0, 'INITIAL', '{}'::jsonb, ?, 0, NOW())""",
                    revision, po, submitter);
            int lineNo = 1;
            for (String sku : List.of(SOFA, TABLE)) {
                jdbc.update("""
                        INSERT INTO procurement.purchase_order_lines (id, po_id, po_revision_id, line_no,
                            inventory_item_id, ordered_qty, unit_price, version, created_at)
                        SELECT ?, ?, ?, ?, id, 10, 1000, 0, NOW() FROM inventory.inventory_items WHERE sku = ?""",
                        Identifiers.newId(), po, revision, lineNo++, sku);
            }
        });
        return po;
    }

    private UUID poLine(UUID po, String sku) {
        return jdbc.queryForObject("""
                SELECT l.id FROM procurement.purchase_order_lines l JOIN inventory.inventory_items i
                  ON i.id = l.inventory_item_id WHERE l.po_id = ? AND i.sku = ?""", UUID.class, po, sku);
    }

    private UUID lineOf(GoodsReceipts.ReceiptView view, UUID po, String sku) {
        UUID poLine = poLine(po, sku);
        return view.lines().stream().filter(line -> line.purchaseOrderLineId().equals(poLine)).findFirst().orElseThrow().id();
    }

    private static byte[] jpeg(int width, int height) {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(Color.DARK_GRAY);
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        try (var out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "jpeg", out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static IncomingFiles photo(String name) {
        byte[] bytes = jpeg(400, 300);
        return IncomingFiles.sent(List.of(new FileUpload(name, "image/jpeg", bytes.length,
                new ByteArrayInputStream(bytes))));
    }

    private static ErrorCode code(Runnable action) {
        Throwable failure = catchThrowable(action::run);
        assertThat(failure).isInstanceOf(BusinessException.class);
        return ((BusinessException) failure).errorCode();
    }

    private String keyOf(UUID photoId) {
        return jdbc.queryForObject("SELECT storage_key FROM procurement.goods_receipt_photos WHERE id = ?", String.class,
                photoId);
    }

    @Test
    @DisplayName("receiving photos while counting, QC photos of the QC line while in QC: kept, listed, shown, audited")
    void photosOfEachStepAreKeptListedAndLinked() {
        PhotoView pallet = photos.add(receipt, null, GoodsReceiptPhotoStage.RECEIPT, photo("pallet.jpg"), clerk);
        PhotoView crate = photos.add(receipt, sofaLine, GoodsReceiptPhotoStage.RECEIPT, photo("thung-sofa.jpg"), clerk);
        assertThat(code(() -> photos.add(receipt, tableLine, GoodsReceiptPhotoStage.QC, photo("early.jpg"), inspector)))
                .as("QC photos wait for QC").isEqualTo(ErrorCode.INVALID_RECEIPT_TRANSITION);

        assertThat(receipts.confirm(receipt, clerk).status()).isEqualTo(GoodsReceiptStatus.IN_QC);
        assertThat(code(() -> photos.add(receipt, null, GoodsReceiptPhotoStage.RECEIPT, photo("late.jpg"), clerk)))
                .as("the count is closed").isEqualTo(ErrorCode.INVALID_RECEIPT_TRANSITION);
        assertThat(code(() -> photos.add(receipt, sofaLine, GoodsReceiptPhotoStage.QC, photo("sofa.jpg"), inspector)))
                .as("the sofa goes through no QC").isEqualTo(ErrorCode.INVALID_RECEIPT_TRANSITION);
        assertThat(code(() -> photos.add(receipt, null, GoodsReceiptPhotoStage.QC, photo("none.jpg"), inspector)))
                .as("a QC photo is of a line").isEqualTo(ErrorCode.VALIDATION_FAILED);
        PhotoView scratch = photos.add(receipt, tableLine, GoodsReceiptPhotoStage.QC, photo("vet-xuoc.jpg"), inspector);

        assertThat(photos.list(receipt)).extracting(PhotoView::id, PhotoView::stage, PhotoView::receiptLineId)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(pallet.id(), GoodsReceiptPhotoStage.RECEIPT, null),
                        org.assertj.core.groups.Tuple.tuple(crate.id(), GoodsReceiptPhotoStage.RECEIPT, sofaLine),
                        org.assertj.core.groups.Tuple.tuple(scratch.id(), GoodsReceiptPhotoStage.QC, tableLine));
        assertThat(scratch.fileName()).isEqualTo("vet-xuoc.jpg");
        assertThat(scratch.takenBy()).isEqualTo(inspector);
        assertThat(scratch.takenAt()).isNotNull();

        String key = keyOf(scratch.id());
        assertThat(key).startsWith("qc-evidence/").endsWith(".jpg");
        assertThat(storage.exists(key)).isTrue();
        assertThat(jdbc.queryForObject("SELECT checksum FROM procurement.goods_receipt_photos WHERE id = ?",
                String.class, scratch.id())).matches("[0-9a-f]{64}");
        String display = StorageKeys.evidenceCopyOf(key, 1600);
        assertThat(storage.exists(display)).as("a display copy without the phone's metadata").isTrue();
        assertThat(scratch.thumbnailUrl()).isEqualTo("http://objects.test/" + StorageKeys.evidenceCopyOf(key, 256));

        assertThat(photos.viewLink(receipt, scratch.id()).url()).isEqualTo("http://objects.test/" + display);
        assertThat(photos.originalLink(receipt, scratch.id()).url()).isEqualTo("http://objects.test/" + key);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform.audit_log WHERE resource_type = 'goods-receipt-photo'
                   AND action = 'EXPORT' AND resource_id = ?""", Integer.class, scratch.id().toString())).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform.audit_log WHERE resource_type = 'goods-receipt-photo'
                   AND action = 'CREATE' AND resource_id = ?""", Integer.class, scratch.id().toString())).isOne();
    }

    @Test
    @DisplayName("a subject holds at most ten photos; the eleventh is refused before anything is stored")
    void photosAreBoundedPerSubject() {
        for (int i = 0; i < GoodsReceiptPhotoService.MAX_PHOTOS_PER_SUBJECT; i++) {
            photos.add(receipt, null, GoodsReceiptPhotoStage.RECEIPT, photo("p" + i + ".jpg"), clerk);
        }
        clearInvocations(storage);
        assertThat(code(() -> photos.add(receipt, null, GoodsReceiptPhotoStage.RECEIPT, photo("p10.jpg"), clerk)))
                .isEqualTo(ErrorCode.GOODS_RECEIPT_PHOTO_LIMIT_REACHED);
        verify(storage, never()).store(any(), any(), any(), anyLong(), any(), any());
        // Each subject counts on its own: the delivery is full, a line is not.
        photos.add(receipt, sofaLine, GoodsReceiptPhotoStage.RECEIPT, photo("line.jpg"), clerk);
        assertThat(photos.list(receipt)).hasSize(GoodsReceiptPhotoService.MAX_PHOTOS_PER_SUBJECT + 1);
    }

    @Test
    @DisplayName("an unknown receipt, a line of another receipt, another warehouse's staff: refused, nothing stored")
    void refusalsKeepNothing() {
        UUID kept = photos.add(receipt, null, GoodsReceiptPhotoStage.RECEIPT, photo("kept.jpg"), clerk).id();
        clearInvocations(storage);

        assertThat(code(() -> photos.add(UUID.randomUUID(), null, GoodsReceiptPhotoStage.RECEIPT, photo("x.jpg"), clerk)))
                .isEqualTo(ErrorCode.GOODS_RECEIPT_NOT_FOUND);
        assertThat(code(() -> photos.add(receipt, UUID.randomUUID(), GoodsReceiptPhotoStage.RECEIPT, photo("x.jpg"),
                clerk))).isEqualTo(ErrorCode.RECEIPT_LINE_NOT_FOUND);
        assertThat(code(() -> photos.viewLink(receipt, UUID.randomUUID()))).isEqualTo(ErrorCode.NOT_FOUND);

        WithCurrentUser.run(TestUsers.warehouseStaff("HN"), () -> {
            assertThat(code(() -> photos.list(receipt))).isEqualTo(ErrorCode.OUT_OF_DATA_SCOPE);
            assertThat(code(() -> photos.viewLink(receipt, kept))).isEqualTo(ErrorCode.OUT_OF_DATA_SCOPE);
            assertThat(code(() -> photos.originalLink(receipt, kept))).isEqualTo(ErrorCode.OUT_OF_DATA_SCOPE);
            assertThat(code(() -> photos.add(receipt, null, GoodsReceiptPhotoStage.RECEIPT, photo("x.jpg"), clerk)))
                    .isEqualTo(ErrorCode.OUT_OF_DATA_SCOPE);
        });
        verify(storage, never()).store(any(), any(), any(), anyLong(), any(), any());
        assertThat(photos.list(receipt)).hasSize(1);
    }

    @Test
    @DisplayName("a browser upload named again answers with the photo it made, without accepting the upload twice")
    void aRepeatedUploadAnswersWithItsPhoto() {
        PhotoView first = photos.add(receipt, sofaLine, GoodsReceiptPhotoStage.RECEIPT, photo("a.jpg"), clerk);
        UUID uploadId = UUID.randomUUID();
        doReturn(Optional.of(keyOf(first.id()))).when(staged).keyOf(FileCategory.QC_EVIDENCE, clerk, uploadId);

        PhotoView again = photos.add(receipt, sofaLine, GoodsReceiptPhotoStage.RECEIPT,
                IncomingFiles.uploaded(clerk, List.of(uploadId)), clerk);

        assertThat(again.id()).isEqualTo(first.id());
        verify(staged, never()).accept(any(), any(), any());
        assertThat(photos.list(receipt)).hasSize(1);
    }

    @Test
    @DisplayName("a photographed line can still be recounted: the photo stays with the receipt, as one of the delivery")
    void aPhotographedLineDoesNotBlockTheRecount() {
        PhotoView ofTheLine = photos.add(receipt, tableLine, GoodsReceiptPhotoStage.RECEIPT, photo("lo.jpg"), clerk);

        // Correcting the lot makes the line a new one: the old line is dropped from the count.
        var recounted = receipts.replaceLines(receipt, List.of(
                new GoodsReceipts.NewLine(poLine(po, SOFA), 4, null, null, "HCM-RCV01", null),
                new GoodsReceipts.NewLine(poLine(po, TABLE), 6, "PH-FIXED-" + UUID.randomUUID().toString().substring(0, 6),
                        LocalDate.now().plusYears(1), "HCM-RCV01", null)));

        assertThat(lineOf(recounted, po, TABLE)).isNotEqualTo(tableLine);
        assertThat(photos.list(receipt)).singleElement().satisfies(photo -> {
            assertThat(photo.id()).isEqualTo(ofTheLine.id());
            assertThat(photo.receiptLineId()).as("its line is gone, the photo is not").isNull();
            assertThat(photo.stage()).isEqualTo(GoodsReceiptPhotoStage.RECEIPT);
        });
        assertThat(storage.exists(keyOf(ofTheLine.id()))).isTrue();
    }

    @Test
    @DisplayName("the database keeps photos append-only, a QC photo on a QC line, and the sweep sees them as kept")
    void theSchemaStatesTheRulesAgain() {
        UUID kept = photos.add(receipt, null, GoodsReceiptPhotoStage.RECEIPT, photo("kept.jpg"), clerk).id();
        String key = keyOf(kept);

        assertThatThrownBy(() -> jdbc.update("UPDATE procurement.goods_receipt_photos SET original_name = 'x' WHERE id = ?",
                kept)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE procurement.goods_receipt_photos SET receipt_line_id = ? WHERE id = ?",
                sofaLine, kept)).as("a photo is never moved onto a line").isInstanceOf(DataAccessException.class);
        UUID ofTheLine = photos.add(receipt, sofaLine, GoodsReceiptPhotoStage.RECEIPT, photo("line.jpg"), clerk).id();
        assertThatThrownBy(() -> jdbc.update("UPDATE procurement.goods_receipt_photos SET receipt_line_id = NULL WHERE id = ?",
                ofTheLine)).as("only dropping the line unlinks its photo").isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM procurement.goods_receipt_photos WHERE id = ?", kept))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO procurement.goods_receipt_photos (id, receipt_id, receipt_line_id, stage, storage_key,
                    original_name, content_type, size_bytes, checksum, taken_by)
                VALUES (?, ?, ?, 'QC', ?, 'x.jpg', 'image/jpeg', 1, ?, ?)""", Identifiers.newId(), receipt, sofaLine,
                "qc-evidence/2026/11/06/" + Identifiers.newId() + ".jpg", "0".repeat(64), inspector))
                .as("the sofa goes through no QC").isInstanceOf(DataAccessException.class);

        Set<String> referenced = new HashSet<>();
        owners.forEach(owner -> {
            if (owner.categories().contains(FileCategory.QC_EVIDENCE)) {
                referenced.addAll(owner.referenced(FileCategory.QC_EVIDENCE, List.of(key, "qc-evidence/2026/01/01/x.jpg")));
            }
        });
        assertThat(referenced).as("the nightly sweep must not take a receipt photo for an orphan").containsExactly(key);
    }
}
