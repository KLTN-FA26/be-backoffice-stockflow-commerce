package com.stockflow.contracts;

import java.util.UUID;

/**
 * Published by the <b>production</b> module when the customer approves a printed sample of a new
 * design. It is what lets an order line with that design be released (BR-PRD-08): a new design is
 * never mass-printed before the customer has held one.
 *
 * <p>The approval is for a design <i>on a blank</i>: the same artwork on a different cup prints
 * differently, so a listener matches on both {@code designSnapshotId} and {@code blankSku}.</p>
 *
 * <p>Consumers receive it in-process through {@code @ApplicationModuleListener}; delivery is made
 * durable by Spring Modulith's event publication registry. See {@code package-info.java} for the
 * rules on changing this contract.</p>
 */
public record SampleApproved(
        UUID sampleRequestId,
        UUID customerId,
        UUID designSnapshotId,
        String blankSku
) {
}
