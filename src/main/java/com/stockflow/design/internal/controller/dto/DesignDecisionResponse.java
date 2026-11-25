package com.stockflow.design.internal.controller.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * One decision in a design's history. {@code actorName} is who {@code actorId} is (full name, else username), read
 * once for the page, so the history says who submitted, returned or approved it; null for a decision made by no one
 * (a system step) or an account that is gone.
 */
public record DesignDecisionResponse(UUID actorId, UUID artifactId, String decision, String notes,
                                     long version, Instant at, String actorName) {
}
