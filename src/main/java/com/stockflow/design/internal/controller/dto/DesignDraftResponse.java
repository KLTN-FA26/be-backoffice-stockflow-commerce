package com.stockflow.design.internal.controller.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * A design. {@code version} is the optimistic-lock token every write must echo back (kltn-docs 12 BR-11).
 * {@code productSlug}, {@code sku} and {@code templateCode} say what the design is drawn on - the last two are absent for
 * a design staff started before customers designed; {@code previewArtifactId} is the current customer preview, the same
 * as {@code currentArtifactId}; {@code updatedAt} is when the design or its layout last changed.
 */
public record DesignDraftResponse(UUID id, UUID customerId, UUID productId, UUID ownerUserId,
                                  UUID assignedUserId, UUID reviewerUserId, String name, String spec,
                                  String status, UUID currentArtifactId, boolean technicalReviewPassed,
                                  String reviewNotes, long version, UUID parentSnapshotId, String productSlug,
                                  String sku, String templateCode, Instant reviewRequestedAt, Instant reviewedAt,
                                  Instant updatedAt, UUID previewArtifactId) {
}
