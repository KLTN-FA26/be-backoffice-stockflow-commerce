package com.stockflow.production;

import com.stockflow.common.id.Identifiers;
import com.stockflow.common.storage.FileStorage;
import com.stockflow.production.internal.service.SampleProductionProgress;
import com.stockflow.support.ConfirmedDesigns;
import com.stockflow.support.DemoData;
import com.stockflow.support.ReferenceRows;
import jakarta.persistence.EntityManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/**
 * Rows the production integration tests need from other modules, inserted the way those modules leave
 * them: a customer-confirmed design whose stored bytes hash to its checksum (so the real
 * {@code DesignService} verification passes), and a customer with a portal account.
 */
final class ProductionFixtures {

    /** A demo SKU of {@code DemoData.PRODUCT_SOFA} with sellable stock in HCM. */
    static final String BLANK = ConfirmedDesigns.BLANK;

    record Design(UUID snapshotId, String checksum) {
    }

    private final FileStorage storage;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final EntityManager entityManager;
    private final SampleProductionProgress progress;

    ProductionFixtures(FileStorage storage, JdbcTemplate jdbc, TransactionTemplate tx, EntityManager entityManager,
                       SampleProductionProgress progress) {
        this.storage = storage;
        this.jdbc = jdbc;
        this.tx = tx;
        this.entityManager = entityManager;
        this.progress = progress;
    }

    /** A design of the sofa product that {@code customerId} confirmed (see {@link ConfirmedDesigns}). */
    Design confirmedDesign(UUID customerId) throws Exception {
        ConfirmedDesigns.Design design = ConfirmedDesigns.confirmed(storage, jdbc, tx, entityManager, customerId);
        return new Design(design.snapshotId(), design.checksum());
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

    /** The SAMPLE production order of a submitted round. */
    UUID sampleProductionOrder(UUID sampleRequestId) {
        return jdbc.queryForObject("""
                SELECT id FROM production.production_order WHERE type = 'SAMPLE' AND sample_request_id = ?""",
                UUID.class, sampleRequestId);
    }

    /**
     * The workshop reports the round's SAMPLE production order completed through the contract SCRUM-400
     * will call. The production order itself is not moved through prepress, printing and QC: those steps
     * are SCRUM-396..399 and do not exist yet, so this proves the round's side of the hand-off only.
     */
    void workshopFinished(UUID sampleRequestId) {
        progress.productionCompleted(sampleProductionOrder(sampleRequestId));
    }
}
