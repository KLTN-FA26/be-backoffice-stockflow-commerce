package com.stockflow.design.api;

import java.util.UUID;

/** Integrity result is data so fulfillment can persist both MATCH and MISMATCH evidence. */
public record DesignVerification(UUID snapshotId, String expectedChecksum, String actualChecksum,
                                 boolean matching) { }
