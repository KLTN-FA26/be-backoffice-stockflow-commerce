package com.stockflow.design.internal.controller.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A customer-confirmed, immutable design snapshot as Sales picks it for a quote line or a sample round
 * ({@code designSnapshotId}). Metadata only: the picture is fetched separately, as a short-lived link.
 * {@code confirmedByName} is who {@code confirmedBy} is (full name, else username), read once for the page; null when
 * the account is gone.
 */
public record ConfirmedSnapshotResponse(UUID snapshotId, UUID draftId, String designName, UUID customerId,
                                        UUID productId, String checksum, String spec, Instant confirmedAt,
                                        UUID confirmedBy, List<DesignSnapshotResponse.Artifact> artifacts,
                                        String confirmedByName) {
}
