package com.stockflow.contracts;

import java.time.Instant;
import java.util.UUID;

/**
 * Published by the <b>order</b> module when the customer approves a production order's print sample, so mass printing
 * starts (kltn-docs 17 §4.3 steps 9-10; open question B31): once per production order. The <b>notification</b> module
 * tells the producer "sample approved, print the batch" through the channel the production order went by, with fresh
 * links to the print files, which the first message's may have outlived.
 *
 * <p>The sample rounds themselves are 4A-4's; this is the message they end with. Facts only, as
 * {@link ProductionOrderSent}. Consumers receive it in-process through {@code @ApplicationModuleListener}; see
 * {@code package-info.java} for the rules on changing this contract.</p>
 */
public record ProductionOrderSampleApproved(UUID eventId, UUID productionOrderId, String code, Instant approvedAt) {
}
