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

    /**
     * One run's output as it was recorded (or the run a stop recorded), with when: what the dashboard counts as
     * printed in a period (QA3). The order's own counters stay the running totals.
     */
    void recordPrintRun(UUID productionOrderId, int printed, int scrap, Instant at, UUID by);

    void recordScrap(Scrap scrap);

    /**
     * A goods receipt of a subcontracted child (G9) and, for SUPPLIED_BLANKS, its blank reconciliation (decision
     * Q9): {@code usedBlanks} — the blanks the received goods were printed on — is written with the receipt;
     * {@code spoiltBlanks} and {@code returnedBlanks} once the warehouse reconciles it.
     */
    record SubcontractReceipt(UUID receiptId, UUID productionOrderId, int quantity, Instant receivedAt,
                              Integer usedBlanks, Integer spoiltBlanks, Integer returnedBlanks, Instant reconciledAt,
                              UUID reconciledBy) {

        public boolean reconciled() {
            return reconciledAt != null;
        }
    }

    /**
     * Counts a goods receipt of a subcontracted child once: false when this receipt was counted before.
     * {@code usedBlanks}: null for FULL_SERVICE, whose blanks are the subcontractor's.
     */
    boolean recordSubcontractReceipt(UUID receiptId, UUID productionOrderId, int quantity, Integer usedBlanks,
                                     Instant at);

    List<SubcontractReceipt> subcontractReceiptsOf(UUID productionOrderId);

    /** Locks the row: two reconciliations of one receipt run one after the other. */
    Optional<SubcontractReceipt> findSubcontractReceiptForUpdate(UUID receiptId);

    /** Records the reconciliation once: false when the receipt was reconciled before. */
    boolean reconcileSubcontractReceipt(UUID receiptId, int spoilt, int returned, Instant at, UUID by);

    /** The hold a receipt line's goods were taken into for the child (decision Q8). */
    void recordSubcontractHold(UUID reservationId, UUID receiptId, UUID receiptLineId, UUID productionOrderId);

    /** The holds of the child's received goods, in receipt order. */
    List<UUID> subcontractHoldsOf(UUID productionOrderId);

    /**
     * Blanks brought back from a subcontractor without a goods receipt (BR-PRD-15): {@code returned} back to stock,
     * {@code spoilt} written off as SUBCONTRACT_LOSS. One row per request.
     */
    record SubcontractRecall(UUID id, UUID productionOrderId, UUID requestId, int returned, int spoilt,
                             String returnLocation, String note, Instant recordedAt, UUID recordedBy) {
    }

    Optional<SubcontractRecall> findRecall(UUID requestId);

    void recordRecall(SubcontractRecall recall);

    List<SubcontractRecall> recallsOf(UUID productionOrderId);
}
