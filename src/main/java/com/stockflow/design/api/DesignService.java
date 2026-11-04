package com.stockflow.design.api;

import java.util.UUID;
/** Cross-module verification of customer-confirmed, immutable design content. */
public interface DesignService {

    /** Resolves only a customer-owned confirmed snapshot and verifies its stored bytes. */
    ConfirmedDesign verifySnapshot(UUID snapshotId, UUID customerId);
    ConfirmedDesign verifySnapshotForSku(UUID snapshotId, UUID customerId, String sku);

    DesignVerification verifyForFulfillment(UUID snapshotId, String expectedChecksum);

    FulfillmentArtifact fulfillmentDownload(UUID snapshotId, String expectedChecksum, DesignArtifactRole role);

}
