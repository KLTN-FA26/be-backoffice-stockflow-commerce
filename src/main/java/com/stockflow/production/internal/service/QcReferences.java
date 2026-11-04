package com.stockflow.production.internal.service;

import com.stockflow.common.security.CurrentUser;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What QC looks at next to a production order (SCRUM-418/419, BR-PRD-05), read for that one order and nothing
 * wider: the approved sample an ORDER is checked against, with the picture the customer approved, and the PACKING
 * areas a completing round may send the goods to. Both are scoped like the order itself (back office, the order's
 * warehouse for a warehouse-bound caller), so QC needs neither the design module's file access nor the whole
 * warehouse map.
 */
public interface QcReferences {

    /**
     * The sample round an ORDER production order is checked against ({@code approvedSampleId}, set when the line
     * was released, BR-PRD-08) and the {@code CUSTOMER_PREVIEW} of the confirmed design that round printed - the
     * same immutable snapshot the order prints, verified against the order's frozen checksum before a link is
     * handed out.
     *
     * @throws com.stockflow.common.error.NotFoundException for a SAMPLE, or an ORDER of a repeat design released
     *         without an approved round (there is nothing approved to show)
     */
    ApprovedSample approvedSample(CurrentUser user, UUID productionOrderId);

    /** The usable PACKING areas of the order's warehouse, codes in order; one entry means it is the default. */
    List<String> packingLocations(CurrentUser user, UUID productionOrderId);

    /**
     * @param approvedAt             when the customer approved the round
     * @param sampleProductionOrderId the SAMPLE production order that printed the physical sample; null if none
     * @param preview                null when the confirmed bundle holds no CUSTOMER_PREVIEW file
     */
    record ApprovedSample(UUID productionOrderId, String productionOrderNumber, UUID sampleRequestId,
                          String sampleRequestNumber, int round, Instant approvedAt, UUID designSnapshotId,
                          String designChecksum, UUID sampleProductionOrderId, String sampleProductionOrderNumber,
                          Preview preview) {
    }

    /** A short-lived private link: {@code url} stops working at {@code expiresAt}; ask again for a new one. */
    record Preview(UUID artifactId, String originalName, String contentType, long sizeBytes, String checksum,
                   String url, Instant expiresAt) {
    }
}
