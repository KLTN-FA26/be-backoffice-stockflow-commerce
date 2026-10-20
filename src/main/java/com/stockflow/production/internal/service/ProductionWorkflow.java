package com.stockflow.production.internal.service;

import com.stockflow.common.security.CurrentUser;
import com.stockflow.production.internal.domain.ProductionOrder;

import java.time.Instant;
import java.util.UUID;

/**
 * The workshop's verbs on a production order (03-state-machines §16, SCRUM-396..401). Each call locks the
 * order, applies one transition, records it on the status history and returns the order as read. A
 * warehouse-bound caller acts only on orders of their warehouses.
 */
public interface ProductionWorkflow {

    /** Where to download the PRINT_READY file the order prints, verified against its checksum. */
    record PrintFile(UUID artifactId, String originalName, String contentType, long sizeBytes, String checksum,
                     String url, Instant expiresAt) {
    }

    /** SCRUM-407. A short-lived link to the order's PRINT_READY artifact (design :: api). */
    PrintFile printFile(CurrentUser user, UUID productionOrderId);

    /**
     * SCRUM-407. PASS: the stored file still matches the locked checksum (BR-PRD-02), PENDING_PREPRESS →
     * READY. FAIL: ON_HOLD with reason FILE_REJECTED and the note, Sales notified.
     */
    ProductionOrders.Detail prepress(CurrentUser user, UUID productionOrderId, boolean pass, String note);

    /** Stop work with a reason; resume returns to the state it interrupted. */
    ProductionOrders.Detail hold(CurrentUser user, UUID productionOrderId, ProductionOrder.HoldReason reason,
                                 String note);

    ProductionOrders.Detail resume(CurrentUser user, UUID productionOrderId);
}
