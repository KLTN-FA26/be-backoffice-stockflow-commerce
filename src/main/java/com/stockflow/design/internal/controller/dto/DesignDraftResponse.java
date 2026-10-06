package com.stockflow.design.internal.controller.dto;

import java.util.UUID;

/** {@code version} is the optimistic-lock token every workflow request must echo back. */
public record DesignDraftResponse(UUID id, UUID customerId, UUID productId, UUID ownerUserId,
                                  UUID assignedUserId, UUID reviewerUserId, String name, String spec,
                                  String status, UUID currentArtifactId, boolean technicalReviewPassed,
                                  String reviewNotes, long version, UUID parentSnapshotId) {
}
