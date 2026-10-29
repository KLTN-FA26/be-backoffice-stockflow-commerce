package com.stockflow.production.api;

import java.time.Instant;
import java.util.List;

/**
 * One production order with what the list leaves out, as {@link ProductionOrderQueries#byNumber} returns it: how far
 * the blanks and the press have got, what stopped the work, and how it got where it is. All of it is on the production
 * order's detail screen, the blanks still needed as the sum of its counts; what QC passed is not, it is the QC results'
 * own screen ({@code production-qc-results}), so it is not here either.
 *
 * @param order             the order as a list shows it
 * @param origin            why a child exists: {@code SPLIT} (part sent to a subcontractor), {@code SHORTFALL} (what a
 *                          subcontractor owed, taken back to the print shop) or {@code REPLACEMENT}; null for a root
 * @param issuedQuantity    blanks that reached the print shop
 * @param printedQuantity   of them, printed; each became good or scrap (BR-PRD-04)
 * @param scrapQuantity     units spoilt on the press or failed at QC
 * @param blanksStillNeeded blanks still to be issued before the plan can be printed (BR-PRD-03): the good units
 *                          planned, less the good units made, less the blanks in the print shop not printed yet. Null
 *                          where no blank is issued to the print shop any more: a SUBCONTRACTED order (its blanks go
 *                          out with it, or the subcontractor buys them), and a COMPLETED or CANCELLED one
 * @param hold              what stopped the work, while ON_HOLD; null otherwise
 * @param prepressCheckedAt when the print file passed prepress; null before
 * @param completedAt       when QC passed the last of the planned good units
 * @param cancellationReason typed by the person who cancelled it
 * @param history           every status change, oldest first
 */
public record ProductionOrderDetail(ProductionOrderView order, String origin, int issuedQuantity, int printedQuantity,
                                    int scrapQuantity, Integer blanksStillNeeded, Hold hold,
                                    Instant prepressCheckedAt, Instant completedAt, Instant cancelledAt,
                                    String cancellationReason, List<StatusChange> history) {

    public ProductionOrderDetail {
        history = List.copyOf(history);
    }

    /**
     * @param reason    one of {@link ProductionOrderQueries#HOLD_REASONS}
     * @param note      what the person who stopped the work typed; null when they typed nothing
     * @param resumesTo the status work goes back to when it is resumed
     * @param since     when the work was stopped; null when the history does not say
     */
    public record Hold(String reason, String note, String resumesTo, Instant since) {
    }

    /** @param reason typed by the person who made the change, or written by the system; null when there is none */
    public record StatusChange(String fromStatus, String toStatus, String reason, Instant changedAt) {
    }
}
