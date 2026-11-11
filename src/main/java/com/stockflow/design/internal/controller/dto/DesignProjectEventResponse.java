package com.stockflow.design.internal.controller.dto;

import java.time.Instant;
import java.util.UUID;

/** One step of a design project's history (kltn-docs 12 §5.4). */
public record DesignProjectEventResponse(UUID actorId, String event, String notes, Instant createdAt) {
}
