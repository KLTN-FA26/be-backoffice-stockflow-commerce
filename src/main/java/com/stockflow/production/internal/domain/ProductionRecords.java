package com.stockflow.production.internal.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence port of what the workshop records against a production order, append-only: blanks issued
 * ({@code production_material_issue}), QC rounds ({@code production_qc_result}) and scrap
 * ({@code production_scrap}). The order's own counters live on the aggregate; these rows say where each
 * number came from.
 */
public interface ProductionRecords {

    /** One hold issued to the print shop, whole (SCRUM-397). */
    record MaterialIssue(UUID id, UUID productionOrderId, UUID requestId, UUID reservationId, String sku,
                         String lotNumber, String fromLocation, String toLocation, int quantity,
                         UUID stockMovementId, Instant issuedAt, UUID issuedBy) {
    }

    /** One QC round (SCRUM-399, BR-PRD-05). */
    record QcRound(UUID id, UUID productionOrderId, int round, int passed, int failed, String note,
                   Instant inspectedAt, UUID inspectedBy) {
    }

    /** {@code ck_production_scrap_reason}. */
    enum ScrapReason { PRINT_DEFECT, DAMAGED_BLANK, QC_FAILED, SETUP, OTHER }

    /**
     * Scrapped units with their reason (BR-PRD-04) and the stock adjustment that writes them off, if the
     * write-off was asked for (it is decided by a different person, SCRUM-145).
     */
    record Scrap(UUID id, UUID productionOrderId, int quantity, ScrapReason reason, String note,
                 UUID stockAdjustmentId, Instant recordedAt, UUID recordedBy) {
    }

    Optional<MaterialIssue> findIssue(UUID requestId);

    List<MaterialIssue> issuesOf(UUID productionOrderId);

    void recordIssue(MaterialIssue issue);

    List<QcRound> qcRoundsOf(UUID productionOrderId);

    void recordQc(QcRound round);

    List<Scrap> scrapOf(UUID productionOrderId);

    void recordScrap(Scrap scrap);

    /** Counts a goods receipt of a subcontracted child once: false when this receipt was counted before. */
    boolean recordSubcontractReceipt(UUID receiptId, UUID productionOrderId, int quantity, Instant at);
}
