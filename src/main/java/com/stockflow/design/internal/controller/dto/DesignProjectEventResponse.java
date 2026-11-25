package com.stockflow.design.internal.controller.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * One step of a design project's history (kltn-docs 12 §5.4). {@code actorName} is who {@code actorId} is (full name,
 * else username), read once for the page; null for a step no one took or an account that is gone.
 */
public record DesignProjectEventResponse(UUID actorId, String event, String notes, Instant createdAt,
                                         String actorName) {
}
