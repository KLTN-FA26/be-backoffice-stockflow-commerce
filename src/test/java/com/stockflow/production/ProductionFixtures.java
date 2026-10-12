package com.stockflow.production;

import com.stockflow.common.id.Identifiers;
import com.stockflow.common.storage.FileCategory;
import com.stockflow.common.storage.FileStorage;
import com.stockflow.common.storage.StoredFile;
import com.stockflow.support.DemoData;
import com.stockflow.support.ReferenceRows;
import jakarta.persistence.EntityManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Rows the production integration tests need from other modules, inserted the way those modules leave
 * them: a customer-confirmed design whose stored bytes hash to its checksum (so the real
 * {@code DesignService} verification passes), and a customer with a portal account.
 */
final class ProductionFixtures {

    /** A demo SKU of {@code DemoData.PRODUCT_SOFA} with sellable stock in HCM. */
    static final String BLANK = "SOFA-3S-GREY";

    record Design(UUID snapshotId, String checksum) {
    }

    private final FileStorage storage;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final EntityManager entityManager;

    ProductionFixtures(FileStorage storage, JdbcTemplate jdbc, TransactionTemplate tx, EntityManager entityManager) {
        this.storage = storage;
        this.jdbc = jdbc;
        this.tx = tx;
        this.entityManager = entityManager;
    }

    /** A design of the sofa product that {@code customerId} confirmed. */
    Design confirmedDesign(UUID customerId) throws Exception {
        byte[] bytes = ("%PDF-1.4\n% sample art " + UUID.randomUUID()).getBytes(StandardCharsets.US_ASCII);
        String checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        StoredFile file = storage.store(FileCategory.DESIGN_RENDER, "art.pdf", "application/pdf", bytes.length,
                new ByteArrayInputStream(bytes));
        UUID draft = Identifiers.newId();
        UUID artifact = Identifiers.newId();
        UUID snapshot = Identifiers.newId();
        UUID designer = user();
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

    Design confirmedDesign() throws Exception {
        return confirmedDesign(DemoData.CUSTOMER_ID);
    }

    UUID user() {
        return tx.execute(s -> ReferenceRows.user(entityManager));
    }

    /** A customer whose portal account is {@code userId}. */
    UUID customerOf(UUID userId) {
        UUID id = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO customer.customer (id, user_id, full_name, email, status, version, created_at)
                VALUES (?, ?, 'Portal customer', ?, 'ACTIVE', 0, NOW())""", id, userId, "portal-" + id + "@example.com"));
        return id;
    }

    /** SCRUM-396..401 stand-in: the workshop made the sample and it passed QC. */
    void workshopFinished(UUID sampleRequestId) {
        tx.executeWithoutResult(s -> jdbc.update(
                "UPDATE production.sample_request SET status = 'READY' WHERE id = ? AND status = 'SUBMITTED'",
                sampleRequestId));
    }
}
