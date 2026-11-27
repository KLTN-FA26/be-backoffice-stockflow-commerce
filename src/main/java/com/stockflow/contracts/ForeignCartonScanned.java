package com.stockflow.contracts;

import java.time.Instant;
import java.util.UUID;

/**
 * Published by the <b>fulfillment</b> module when a code that is not a carton of the task is scanned at the carton check
 * station - another order's carton, or a code that is no carton at all (kltn-docs 08 §4.2 and its edge case: such a scan
 * is refused and the warehouse manager is told; WF4-LPN-FOREIGN). The refusal stands; the <b>notification</b> module
 * tells the warehouse manager of the task's warehouse in-app.
 *
 * <p>Published in a transaction of its own, because the scan is refused and the refusal rolls its own transaction back.
 * It carries codes only: the scanned text is cut to {@value #LPN_MAX} characters. Consumers receive it in-process
 * through {@code @ApplicationModuleListener}; see {@code package-info.java} for the rules on changing this contract.</p>
 *
 * @param taskId      the pick (fulfillment) task whose carton check session was being worked
 * @param orderId     the order of that task
 * @param orderNumber its order number
 * @param warehouseId the task's warehouse
 * @param lpn         what was scanned, cut to {@value #LPN_MAX} characters
 * @param scannedBy   who scanned it
 */
public record ForeignCartonScanned(UUID eventId, UUID taskId, UUID orderId, String orderNumber, UUID warehouseId,
                                   String lpn, UUID scannedBy, Instant scannedAt) {

    public static final int LPN_MAX = 64;
}
