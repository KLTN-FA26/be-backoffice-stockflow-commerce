package com.stockflow.support;

import com.stockflow.common.id.Identifiers;
import com.stockflow.common.storage.FileCategory;
import com.stockflow.common.storage.FileStorage;
import com.stockflow.common.storage.StoredFile;
import jakarta.persistence.EntityManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

/**
 * A design a customer confirmed, as the design module leaves it: real stored bytes, a PRINT_READY artifact in
 * the snapshot's manifest whose checksum is the snapshot checksum, a reviewer and a confirmer — so
 * {@code DesignService.verifySnapshotForSku} passes for real, not through a mock, and the print file can be
 * downloaded (prepress, the subcontract dispatch note). A manifest-less snapshot would be the legacy shape, which
 * verifies but has no PRINT_READY file to hand out. The product is {@link DemoData#PRODUCT_SOFA}, whose SKU
 * {@link #BLANK} has stock in HCM.
 *
 * <p>Needs the upload scanner off ({@code stockflow.upload-inspection.enabled=false}): there is no ClamAV
 * container in tests, and switching it off is the supported developer setting.</p>
 */
public final class ConfirmedDesigns {

    /** A demo SKU of {@code DemoData.PRODUCT_SOFA} with sellable stock in HCM. */
    public static final String BLANK = "SOFA-3S-GREY";

    public record Design(UUID snapshotId, String checksum) {
    }

    private ConfirmedDesigns() {
    }

    /**
     * At least {@code units} sellable {@link #BLANK} free in HCM, topped up with a fresh layer received a while ago
     * when there are fewer. A sample's blanks are held from its submission (decision Q1), so a test that submits
     * samples brings its own blanks instead of eating the 33 demo units the checkout tests count on — and only
     * up to {@code units}, so the free stock stays small enough for a test to ask for more than there is.
     */
    public static void stockBlanks(JdbcTemplate jdbc, TransactionTemplate tx, int units) {
        tx.executeWithoutResult(s -> {
            Integer free = jdbc.queryForObject("""
                    SELECT COALESCE(SUM(on_hand - reserved), 0)::int FROM inventory.stock_item
                     WHERE sku = ? AND status = 'AVAILABLE' AND location_code LIKE 'HCM-%'""", Integer.class, BLANK);
            if (free < units) {
                jdbc.update("""
                        INSERT INTO inventory.stock_item (id, sku, location_code, on_hand, reserved, status, version,
                            created_at, received_at)
                        VALUES (?, ?, 'HCM-A02-1-A', ?, 0, 'AVAILABLE', 0, NOW(),
                            clock_timestamp() - interval '2 days' - random() * interval '300 days')""",
                        Identifiers.newId(), BLANK, units - free);
            }
        });
    }

    public static Design confirmed(FileStorage storage, JdbcTemplate jdbc, TransactionTemplate tx, EntityManager em,
                                   UUID customerId) throws Exception {
        byte[] bytes = ("%PDF-1.4\n% print art " + UUID.randomUUID()).getBytes(StandardCharsets.US_ASCII);
        String fileChecksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        StoredFile file = storage.store(FileCategory.DESIGN_RENDER, "art.pdf", "application/pdf", bytes.length,
                new ByteArrayInputStream(bytes));
        UUID draft = Identifiers.newId();
        UUID artifact = Identifiers.newId();
        UUID snapshot = Identifiers.newId();
        // DesignSnapshotJpaEntity.manifestChecksum over the one entry: "ROLE:artifactId:checksum\n".
        String checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                ("PRINT_READY:" + artifact + ":" + fileChecksum + "\n").getBytes(StandardCharsets.UTF_8)));
        String manifest = """
                [{"artifactId": "%s", "role": "PRINT_READY", "storageKey": "%s", "originalName": "art.pdf",
                  "contentType": "application/pdf", "sizeBytes": %d, "checksum": "%s"}]"""
                .formatted(artifact, file.key(), bytes.length, fileChecksum);
        UUID designer = tx.execute(s -> ReferenceRows.user(em));
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO design.design_draft (id, customer_id, product_id, status, created_at, current_artifacts)
                    VALUES (?, ?, ?, 'DRAFT', NOW(), '{}'::jsonb)""", draft, customerId, DemoData.PRODUCT_SOFA);
            jdbc.update("""
                    INSERT INTO design.design_artifact (id, draft_id, storage_key, original_name, content_type,
                        size_bytes, stored_at, checksum, created_at, artifact_role)
                    VALUES (?, ?, ?, 'art.pdf', 'application/pdf', ?, NOW(), ?, NOW(), 'PRINT_READY')""",
                    artifact, draft, file.key(), bytes.length, fileChecksum);
            jdbc.update("""
                    INSERT INTO design.design_snapshot (id, draft_id, checksum, artifact_url, confirmed_at, created_at,
                        artifact_manifest, artifact_id, confirmed_by, reviewed_by, spec)
                    VALUES (?, ?, ?, ?, NOW(), NOW(), ?::jsonb, ?, ?, ?, 'Logo front, 2 colours')""",
                    snapshot, draft, checksum, file.key(), manifest, artifact, designer, designer);
        });
        return new Design(snapshot, checksum);
    }
}
