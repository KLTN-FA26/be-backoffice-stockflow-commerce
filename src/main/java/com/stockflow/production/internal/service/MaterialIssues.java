package com.stockflow.production.internal.service;

import com.stockflow.common.security.CurrentUser;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Blanks issued to the print shop (SCRUM-397, 03 §16 READY → MATERIAL_ISSUED, actor Warehouse staff).
 *
 * <p>One scan issues one hold, whole, from where it is held to a {@code PRODUCTION} area of the order's
 * warehouse: an ORDER's blanks are the holds the order took at checkout; a SAMPLE's are the holds taken
 * when it was submitted (decision Q1); a reprint round's are held for the production order when the
 * blanks are asked for. The hold stays on the goods all the way — nothing is released and taken again —
 * so no other order can take them, and printed goods are never free stock (Q3).</p>
 */
public interface MaterialIssues {

    /**
     * @param requestId       the scan's idempotency key: a retry returns the first answer and moves nothing
     * @param toLocation      a PRODUCTION area of the order's warehouse
     * @param reservationId   the hold scanned; null for the next hold of the order not yet issued
     * @param scannedLocation the location scanned where the blanks were taken; when given it must be the hold's
     *                        ({@code ISSUE_LOCATION_MISMATCH}), any case
     * @param scannedCode     the code scanned on the blanks; when given it must be the carton's LPN (any case) for a
     *                        hold in a carton (SCRUM-473), else the hold's SKU (any case) or the SKU's barcode
     *                        ({@code ISSUE_ITEM_MISMATCH})
     */
    record Issue(UUID requestId, String toLocation, UUID reservationId, String scannedLocation, String scannedCode) {

        /** A scan that names no source location and no item: the hold alone decides (the SCRUM-397 contract). */
        public Issue(UUID requestId, String toLocation, UUID reservationId) {
            this(requestId, toLocation, reservationId, null, null);
        }
    }

    /**
     * What the warehouse still has to bring to the print shop (SCRUM-410: required against scanned).
     *
     * @param required    blanks this order needs issued in all: {@code issued + outstanding}
     * @param outstanding blanks still to issue (BR-PRD-03 caps every scan at this)
     * @param holds       the holds waiting to be issued, oldest first, where their blanks are now. Empty while
     *                    blanks are still outstanding means none is held yet: the next scan holds them in the
     *                    order's warehouse (a reprint, or a round resumed after BLANKS_SHORT)
     */
    record Pending(UUID productionOrderId, String orderNumber, String status, String blankSku, int required,
                   int issued, int outstanding, List<PendingHold> holds) {
    }

    /**
     * One hold to scan: walk to {@code locationCode}, scan it, then the SKU or {@code barcode} - or, for a hold in a
     * carton (SCRUM-473), that carton's label: the hold is deducted from that carton and no other.
     *
     * @param lpn         the carton the held blanks are in, null for loose stock
     * @param wholeCarton the hold is its carton's whole sealed content: the clerk brings the carton as it stands
     */
    record PendingHold(UUID reservationId, String sku, String lotNumber, String locationCode, int quantity,
                       String barcode, String lpn, boolean wholeCarton) {
    }

    record Issued(UUID id, UUID productionOrderId, UUID requestId, UUID reservationId, String sku, String lotNumber,
                  String fromLocation, String toLocation, int quantity, UUID stockMovementId, Instant issuedAt,
                  UUID issuedBy) {
    }

    record Result(Issued issue, ProductionOrders.Detail order) {
    }

    Result issue(CurrentUser user, UUID productionOrderId, Issue issue);

    List<Issued> list(CurrentUser user, UUID productionOrderId);

    Pending pending(CurrentUser user, UUID productionOrderId);
}
