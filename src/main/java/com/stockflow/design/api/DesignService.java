package com.stockflow.design.api;

import java.util.UUID;
/** Cross-module verification of customer-confirmed, immutable design content. */
public interface DesignService {

    /**
     * Resolves only a customer-owned confirmed snapshot, from the database alone: ownership, the
     * confirmation and the recorded manifest. The stored files are checked where they are used
     * ({@link #verifyForFulfillment}, {@link #fulfillmentDownload}, {@link #verifyForPrinting}), so
     * a checkout never waits on the object store.
     */
    ConfirmedDesign verifySnapshot(UUID snapshotId, UUID customerId);
    ConfirmedDesign verifySnapshotForSku(UUID snapshotId, UUID customerId, String sku);

    /**
     * Every stored file of the bundle against the checksum frozen on the order (BR-DSG-003), from the
     * store's recorded SHA-256 - no download unless an object predates it.
     */
    DesignVerification verifyForFulfillment(UUID snapshotId, String expectedChecksum);

    /**
     * {@link #verifyForFulfillment}, re-reading and re-hashing every byte: the last gate before a
     * production order is printed (BR-PRD-02). Costs a full download of the bundle; use it once.
     */
    DesignVerification verifyForPrinting(UUID snapshotId, String expectedChecksum);

    /** A short-lived link to one file of the bundle, after checking that file and the bundle's checksum. */
    FulfillmentArtifact fulfillmentDownload(UUID snapshotId, String expectedChecksum, DesignArtifactRole role);

}
