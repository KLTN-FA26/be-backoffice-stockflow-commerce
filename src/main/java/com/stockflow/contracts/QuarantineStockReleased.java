package com.stockflow.contracts;

import java.util.UUID;

/**
 * Published by the <b>inventory</b> module when a carton released from quarantine has been put away (kltn-docs 05
 * BR-14, 20 §4.8): its units are AVAILABLE on a bin - stock to promise again. {@link PutawayCompleted} is not published
 * for such a task: it is the fact a goods receipt closes on, and a quarantine release belongs to none.
 *
 * <p>The <b>order</b> module consumes it (WF-05 fix plan, section 2): the cartons of a shipment the carrier brought
 * back leave quarantine this way, and are held for their own order again the moment they are available - before any
 * other order is topped up from them.</p>
 *
 * <p>Per task, once: a release task is DONE exactly once. Consumers receive it in-process through
 * {@code @ApplicationModuleListener}; see {@code package-info.java} for the rules on changing this contract.</p>
 *
 * @param lpn          the carton put away
 * @param locationCode the bin (or OVERFLOW area) it is in now
 */
public record QuarantineStockReleased(UUID putawayTaskId, String lpn, String locationCode) {
}
