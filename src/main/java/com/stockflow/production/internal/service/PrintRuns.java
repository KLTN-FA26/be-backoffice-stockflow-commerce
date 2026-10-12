package com.stockflow.production.internal.service;

import com.stockflow.common.security.CurrentUser;
import com.stockflow.production.internal.domain.ProductionRecords;

import java.util.List;
import java.util.UUID;

/**
 * The print shop's steps after the blanks are issued (03-state-machines §16, SCRUM-398..401): printing, its
 * output and spoilage, QC, completion, cancellation and stopping. Each call locks the production order, applies
 * its transitions, records them, and does what they mean to stock through {@code inventory :: api} — all in one
 * transaction.
 */
public interface PrintRuns {

    /** One line of spoilt units with its reason (BR-PRD-04); OTHER needs a note. */
    record ScrapLine(ProductionRecords.ScrapReason reason, int quantity, String note) {
    }

    /** SCRUM-398. MATERIAL_ISSUED → PRINTING; a SAMPLE's round becomes IN_PRODUCTION. */
    ProductionOrders.Detail startPrinting(CurrentUser user, UUID productionOrderId);

    /**
     * SCRUM-398. The run's output: {@code printed} units, of which the scrap lines were spoilt (good + scrap =
     * printed). PRINTING → QC. The spoilt units leave their holds at once and their SCRAP write-off is asked
     * for; the order does not wait for its approval (decision Q4).
     */
    ProductionOrders.Detail recordOutput(CurrentUser user, UUID productionOrderId, int printed, List<ScrapLine> scrap);

    /**
     * SCRUM-399/400. A QC round over every good unit waiting for it (BR-PRD-05: a SAMPLE against the print file,
     * an ORDER against its approved sample, {@code approvedSampleId} on the order). Failed units are scrap.
     * Enough good units: COMPLETED — an ORDER's goods go, still held for it, to {@code packingLocation} (or the
     * warehouse's only PACKING area) and {@code ProductionCompleted} is published; a SAMPLE's are written off as
     * SAMPLE and its round becomes READY. Short: READY, and the missing blanks are held now, or the order goes
     * ON_HOLD (BLANKS_SHORT) and purchasing is told.
     */
    ProductionOrders.Detail recordQc(CurrentUser user, UUID productionOrderId, int passed, int failed, String note,
                                     String packingLocation);

    /**
     * SCRUM-401. Cancel before printing (PENDING_PREPRESS, READY, MATERIAL_ISSUED, or ON_HOLD from one of them):
     * the issued blanks go back to where they came from — still held for the order if the order still wants
     * them — and blanks held but not issued are let go.
     */
    ProductionOrders.Detail cancel(CurrentUser user, UUID productionOrderId, String reason);

    /**
     * SCRUM-401, decision Q2. Stop once printing started (PRINTING, QC, or ON_HOLD from PRINTING): the run's
     * output so far is recorded, the printed goods are kept on the record and written off (sold to nobody else),
     * the unprinted blanks go back. An amount the customer agreed to pay is Sales' to enter on the order.
     */
    ProductionOrders.Detail stop(CurrentUser user, UUID productionOrderId, int printed, List<ScrapLine> scrap,
                                 String reason);

    /** What was spoilt, with the write-off asked for each line. */
    List<ProductionRecords.Scrap> scrapOf(CurrentUser user, UUID productionOrderId);

    /** The QC rounds, first round first. */
    List<ProductionRecords.QcRound> qcRoundsOf(CurrentUser user, UUID productionOrderId);
}
