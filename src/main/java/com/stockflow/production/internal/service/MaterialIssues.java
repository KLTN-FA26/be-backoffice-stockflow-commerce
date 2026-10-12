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
     * @param requestId     the scan's idempotency key: a retry returns the first answer and moves nothing
     * @param toLocation    a PRODUCTION area of the order's warehouse
     * @param reservationId the hold scanned; null for the next hold of the order not yet issued
     */
    record Issue(UUID requestId, String toLocation, UUID reservationId) {
    }

    record Issued(UUID id, UUID productionOrderId, UUID requestId, UUID reservationId, String sku, String lotNumber,
                  String fromLocation, String toLocation, int quantity, UUID stockMovementId, Instant issuedAt,
                  UUID issuedBy) {
    }

    record Result(Issued issue, ProductionOrders.Detail order) {
    }

    Result issue(CurrentUser user, UUID productionOrderId, Issue issue);

    List<Issued> list(CurrentUser user, UUID productionOrderId);
}
