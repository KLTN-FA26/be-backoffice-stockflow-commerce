package com.stockflow.contracts;

import java.util.UUID;

/**
 * Published by the <b>design</b> module. Consumers receive it in-process through
 * {@code @ApplicationModuleListener}; delivery is made durable by Spring Modulith's
 * event publication registry, so a listener that crashes mid-handling gets the event again.
 * See {@code package-info.java} for the rules on changing this contract.
 */
public record DesignConfirmed(
        UUID designSnapshotId,
        UUID customerId,
        String sku,
        String storageUri,
        String checksum
) {
}
