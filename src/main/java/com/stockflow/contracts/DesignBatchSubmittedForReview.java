package com.stockflow.contracts;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Published by the <b>design</b> module when a customer submits several drafts of a design project together and every
 * one passes the preflight (kltn-docs 12 §4.10, BR-19): one review round, one item in the Sales Manager's queue.
 * {@code notification} tells whoever reviews designs once for the round, not once per design. Facts only.
 *
 * @param roundId     the review round
 * @param projectId   the project the designs are in
 * @param customerId  whose designs they are
 * @param designIds   the designs now waiting for review together
 * @param submittedAt when they were submitted
 */
public record DesignBatchSubmittedForReview(UUID roundId, UUID projectId, UUID customerId, List<UUID> designIds,
                                            Instant submittedAt) {

    public DesignBatchSubmittedForReview {
        designIds = List.copyOf(designIds);
    }
}
