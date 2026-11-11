package com.stockflow.design.internal.controller.dto;

import java.time.Instant;
import java.util.UUID;

/** A design project in its owner's list: what it is, and how many designs it holds, archived ones aside. */
public record DesignProjectSummaryResponse(UUID id, String name, String description, String status, long version,
                                           long designCount, Instant createdAt, Instant archivedAt) {
}
