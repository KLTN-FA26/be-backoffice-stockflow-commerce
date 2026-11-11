package com.stockflow.design.internal.controller.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A design project opened by its owner (kltn-docs 12 §4.9): its designs side by side, archived ones aside, and how many
 * files its library holds. {@code version} is the token renaming and closing echo back (BR-11).
 */
public record DesignProjectResponse(UUID id, String name, String description, String status, long version,
                                    Instant createdAt, Instant archivedAt, List<DesignDraftResponse> designs,
                                    long assetCount) {
}
