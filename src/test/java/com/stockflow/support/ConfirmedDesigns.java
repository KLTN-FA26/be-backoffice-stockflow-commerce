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
        return confirmed(storage, jdbc, tx, em, customerId, false, DemoData.PRODUCT_SOFA);
    }

    /** The same for another product than the demo sofa: a test of that product's own life cycle. */
    public static Design confirmedFor(FileStorage storage, JdbcTemplate jdbc, TransactionTemplate tx, EntityManager em,
                                      UUID customerId, UUID productId) throws Exception {
        return confirmed(storage, jdbc, tx, em, customerId, false, productId);
    }

    /**
     * The same, with a {@code CUSTOMER_PREVIEW} picture next to the print file in the confirmed bundle - what the
     * customer looked at when confirming, and what Sales and QC are shown (SCRUM-403/419).
     */
    public static Design confirmedWithPreview(FileStorage storage, JdbcTemplate jdbc, TransactionTemplate tx,
                                              EntityManager em, UUID customerId) throws Exception {
        return confirmed(storage, jdbc, tx, em, customerId, true, DemoData.PRODUCT_SOFA);
    }

    private record Stored(UUID artifactId, String role, String key, String name, String contentType, int size,
                          String checksum) {
    }

    private static Stored store(FileStorage storage, String role, String name, String contentType, byte[] bytes)
            throws Exception {
        String fileChecksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        StoredFile file = storage.store(FileCategory.DESIGN_RENDER, name, contentType, bytes.length,
                new ByteArrayInputStream(bytes));
        return new Stored(Identifiers.newId(), role, file.key(), name, contentType, bytes.length, fileChecksum);
    }

    private static Design confirmed(FileStorage storage, JdbcTemplate jdbc, TransactionTemplate tx, EntityManager em,
                                    UUID customerId, boolean withPreview, UUID productId) throws Exception {
        Stored art = store(storage, "PRINT_READY", "art.pdf", "application/pdf",
                ("%PDF-1.4\n% print art " + UUID.randomUUID()).getBytes(StandardCharsets.US_ASCII));
        java.util.List<Stored> files = new java.util.ArrayList<>();
        if (withPreview) {
            files.add(store(storage, "CUSTOMER_PREVIEW", "preview.png", "image/png", PNG_1X1));
        }
        files.add(art);   // sorted by role name, as DesignSnapshotJpaEntity.manifestChecksum and the manifest are
        UUID draft = Identifiers.newId();
        UUID snapshot = Identifiers.newId();
        MessageDigest manifestDigest = MessageDigest.getInstance("SHA-256");
        StringBuilder manifest = new StringBuilder("[");
        for (Stored f : files) {
            // DesignSnapshotJpaEntity.manifestChecksum: "ROLE:artifactId:checksum\n" per entry, by role name.
            manifestDigest.update((f.role() + ":" + f.artifactId() + ":" + f.checksum() + "\n")
                    .getBytes(StandardCharsets.UTF_8));
            manifest.append(manifest.length() > 1 ? "," : "").append("""
                    {"artifactId": "%s", "role": "%s", "storageKey": "%s", "originalName": "%s",
                     "contentType": "%s", "sizeBytes": %d, "checksum": "%s"}"""
                    .formatted(f.artifactId(), f.role(), f.key(), f.name(), f.contentType(), f.size(), f.checksum()));
        }
        String checksum = HexFormat.of().formatHex(manifestDigest.digest());
        String manifestJson = manifest.append("]").toString();
        UUID designer = tx.execute(s -> ReferenceRows.user(em));
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO design.design_draft (id, customer_id, product_id, status, created_at, current_artifacts)
                    VALUES (?, ?, ?, 'DRAFT', NOW(), '{}'::jsonb)""", draft, customerId, productId);
            for (Stored f : files) {
                jdbc.update("""
                        INSERT INTO design.design_artifact (id, draft_id, storage_key, original_name, content_type,
                            size_bytes, stored_at, checksum, created_at, artifact_role)
                        VALUES (?, ?, ?, ?, ?, ?, NOW(), ?, NOW(), ?)""",
                        f.artifactId(), draft, f.key(), f.name(), f.contentType(), f.size(), f.checksum(), f.role());
            }
            jdbc.update("""
                    INSERT INTO design.design_snapshot (id, draft_id, checksum, artifact_url, confirmed_at, created_at,
                        artifact_manifest, artifact_id, confirmed_by, reviewed_by, spec)
                    VALUES (?, ?, ?, ?, NOW(), NOW(), ?::jsonb, ?, ?, ?, 'Logo front, 2 colours')""",
                    snapshot, draft, checksum, art.key(), manifestJson, art.artifactId(), designer, designer);
        });
        return new Design(snapshot, checksum);
    }

    /** The smallest valid PNG: one transparent pixel. */
    private static final byte[] PNG_1X1 = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==");
}
