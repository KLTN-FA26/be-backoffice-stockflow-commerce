package com.stockflow.procurement.internal.repository;

import com.stockflow.common.storage.FileCategory;
import com.stockflow.common.storage.FileStorage;
import com.stockflow.common.storage.OrphanSweepProperties;
import com.stockflow.common.storage.OrphanedFileSweeper;
import com.stockflow.common.storage.StoredFileReferences;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The files of the purchasing records V20261113000200 archived stay claimed, so the orphaned-file sweep keeps them: a
 * purchase order attachment's file ({@code DOCUMENT}) and a receipt QC photo ({@code QC_EVIDENCE}) are named by their
 * rows in {@code platform.legacy_archive} and by nothing else, and both categories are swept because other owners claim
 * them too (slips, contracts, waybills; damage and receipt photos).
 */
@IntegrationTest
@Import(PostgresContainer.class)
class ArchivedPurchasingFileReferencesIntegrationTest {

    private static final byte[] PDF = "%PDF-1.7\n%%EOF".getBytes();
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0, 0, 0, 0, 0, 0, 1};

    @Autowired ArchivedPurchasingFileReferences references;
    @Autowired FileStorage storage;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void removeTheArchiveRowsWritten() {
        jdbc.update("DELETE FROM platform.legacy_archive WHERE row_data ->> 'archive_test' = 'true'");
    }

    @Test
    void anArchivedAttachmentAndAnArchivedQcPhotoAreClaimedAndNothingElseIs() {
        archive("procurement.purchase_order_attachments", "file_url", "documents/2026/10/01/po-quote.pdf");
        archive("procurement.goods_receipt_photos", "storage_key", "qc-evidence/2026/10/01/qc-photo.jpg");

        assertThat(references.categories()).containsExactlyInAnyOrder(FileCategory.DOCUMENT, FileCategory.QC_EVIDENCE);
        assertThat(references.referenced(FileCategory.DOCUMENT,
                List.of("documents/2026/10/01/po-quote.pdf", "documents/2026/10/01/someone-elses.pdf")))
                .containsExactly("documents/2026/10/01/po-quote.pdf");
        assertThat(references.referenced(FileCategory.QC_EVIDENCE,
                List.of("qc-evidence/2026/10/01/qc-photo.jpg", "qc-evidence/2026/10/01/damage-photo.jpg")))
                .containsExactly("qc-evidence/2026/10/01/qc-photo.jpg");
        assertThat(references.referenced(FileCategory.DOCUMENT, List.of("qc-evidence/2026/10/01/qc-photo.jpg")))
                .as("a key is claimed in its own category only").isEmpty();
        assertThat(references.referenced(FileCategory.QC_EVIDENCE, List.of("documents/2026/10/01/po-quote.pdf")))
                .isEmpty();
        assertThat(references.referenced(FileCategory.DOCUMENT, List.of())).isEmpty();
        assertThat(references.referenced(FileCategory.PRODUCT_IMAGE, List.of("product-images/x.jpg")))
                .as("a category it does not own").isEmpty();
    }

    @Test
    void aTableOtherThanTheTwoArchivedOnesClaimsNothing() {
        archive("procurement.suppliers", "file_url", "documents/2026/10/01/not-a-claim.pdf");
        archive("procurement.purchase_orders", "storage_key", "qc-evidence/2026/10/01/not-a-claim.jpg");

        assertThat(references.referenced(FileCategory.DOCUMENT, List.of("documents/2026/10/01/not-a-claim.pdf")))
                .isEmpty();
        assertThat(references.referenced(FileCategory.QC_EVIDENCE, List.of("qc-evidence/2026/10/01/not-a-claim.jpg")))
                .isEmpty();
    }

    @Test
    void theSweepKeepsTheFilesOfArchivedPurchasingRecordsAndReportsOthers() {
        String archivedAttachment = storage.store(FileCategory.DOCUMENT, "po-quote.pdf", "application/pdf", PDF.length,
                new ByteArrayInputStream(PDF)).key();
        String strayDocument = storage.store(FileCategory.DOCUMENT, "stray.pdf", "application/pdf", PDF.length,
                new ByteArrayInputStream(PDF)).key();
        String archivedPhoto = storage.store(FileCategory.QC_EVIDENCE, "qc.jpg", "image/jpeg", JPEG.length,
                new ByteArrayInputStream(JPEG)).key();
        String strayPhoto = storage.store(FileCategory.QC_EVIDENCE, "stray.jpg", "image/jpeg", JPEG.length,
                new ByteArrayInputStream(JPEG)).key();
        archive("procurement.purchase_order_attachments", "file_url", archivedAttachment);
        archive("procurement.goods_receipt_photos", "storage_key", archivedPhoto);

        // Report only, a month on: files stored just now are old enough to be judged. Only this owner answers, so
        // whatever it does not name in the two categories is an orphan - which is the point.
        List<StoredFileReferences> owners = List.of(references);
        var report = new OrphanedFileSweeper(storage, owners, new OrphanSweepProperties(false, Duration.ofDays(7), 500),
                Clock.offset(Clock.systemUTC(), Duration.ofDays(30))).sweep();

        assertThat(report.orphans()).contains(strayDocument, strayPhoto)
                .doesNotContain(archivedAttachment, archivedPhoto);
        assertThat(report.deleted()).isZero();
        assertThat(storage.exists(archivedAttachment)).isTrue();
        assertThat(storage.exists(archivedPhoto)).isTrue();
    }

    /** Writes a row as V20261113000200 archives them; {@code archive_test} marks it for cleanup. */
    private void archive(String sourceTable, String keyField, String key) {
        String json = "{\"" + keyField + "\": \"" + key + "\", \"archive_test\": \"true\"}";
        jdbc.update("INSERT INTO platform.legacy_archive (source_table, row_id, row_data) VALUES (?, gen_random_uuid(), "
                + "?::jsonb)", sourceTable, json);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.legacy_archive WHERE source_table = ? AND "
                + "row_data ->> ? = ?", Integer.class, sourceTable, keyField, key)).as("the row is there").isEqualTo(1);
    }
}
