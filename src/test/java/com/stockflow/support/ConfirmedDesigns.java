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
 * A design a customer confirmed, as the design module leaves it: real stored bytes whose SHA-256 is the
 * snapshot checksum, an artifact, a reviewer and a confirmer — so {@code DesignService.verifySnapshotForSku}
 * passes for real, not through a mock. The product is {@link DemoData#PRODUCT_SOFA}, whose SKU
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

    public static Design confirmed(FileStorage storage, JdbcTemplate jdbc, TransactionTemplate tx, EntityManager em,
                                   UUID customerId) throws Exception {
        byte[] bytes = ("%PDF-1.4\n% print art " + UUID.randomUUID()).getBytes(StandardCharsets.US_ASCII);
        String checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        StoredFile file = storage.store(FileCategory.DESIGN_RENDER, "art.pdf", "application/pdf", bytes.length,
                new ByteArrayInputStream(bytes));
        UUID draft = Identifiers.newId();
        UUID artifact = Identifiers.newId();
        UUID snapshot = Identifiers.newId();
        UUID designer = tx.execute(s -> ReferenceRows.user(em));
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO design.design_draft (id, customer_id, product_id, status, created_at, current_artifacts)
                    VALUES (?, ?, ?, 'DRAFT', NOW(), '{}'::jsonb)""", draft, customerId, DemoData.PRODUCT_SOFA);
            jdbc.update("""
                    INSERT INTO design.design_artifact (id, draft_id, storage_key, original_name, content_type,
                        size_bytes, stored_at, checksum, created_at, artifact_role)
                    VALUES (?, ?, ?, 'art.pdf', 'application/pdf', ?, NOW(), ?, NOW(), 'PRINT_READY')""",
                    artifact, draft, file.key(), bytes.length, checksum);
            jdbc.update("""
                    INSERT INTO design.design_snapshot (id, draft_id, checksum, artifact_url, confirmed_at, created_at,
                        artifact_manifest, artifact_id, confirmed_by, reviewed_by, spec)
                    VALUES (?, ?, ?, ?, NOW(), NOW(), '[]'::jsonb, ?, ?, ?, 'Logo front, 2 colours')""",
                    snapshot, draft, checksum, file.key(), artifact, designer, designer);
        });
        return new Design(snapshot, checksum);
    }
}
