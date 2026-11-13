package com.stockflow.design.internal.controller.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * A design. {@code version} is the optimistic-lock token every write must echo back (kltn-docs 12 BR-11).
 * {@code productSlug}, {@code sku} and {@code templateCode} say what the design is drawn on - the last two are absent for
 * a design staff started before customers designed; {@code previewArtifactId} is the current customer preview, the same
 * as {@code currentArtifactId}; {@code reviewedBy} and {@code reviewedAt} the Sales Manager's last decision,
 * {@code reviewNotes} their reason when they returned it; {@code updatedAt} is when the design or its layout last changed;
 * {@code projectId} the design project it is in, if any (kltn-docs 12 §4.9). {@code previewUrl} shows the current customer
 * preview inline (a short-lived signed link, absent while there is none), so a list or a project draws its thumbnails
 * without a download link - an existence check and an export audit row - per design; {@code variantName} is the
 * variant's name, or its SKU when it has none to show; {@code productName} names the product even once it is no
 * longer published.
 */
public record DesignDraftResponse(UUID id, UUID customerId, UUID productId, UUID ownerUserId, String name,
                                  String spec, String status, UUID currentArtifactId, UUID reviewedBy,
                                  String reviewNotes, long version, UUID parentSnapshotId, String productSlug,
                                  String sku, String templateCode, Instant reviewRequestedAt, Instant reviewedAt,
                                  Instant updatedAt, UUID previewArtifactId, UUID projectId, String previewUrl,
                                  String variantName, String productName) {
}
