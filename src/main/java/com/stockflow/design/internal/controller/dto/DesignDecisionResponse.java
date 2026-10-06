package com.stockflow.design.internal.controller.dto;

import java.time.Instant;
import java.util.UUID;

public record DesignDecisionResponse(UUID actorId, UUID artifactId, String decision, String notes,
                                     long version, Instant at) {
}
