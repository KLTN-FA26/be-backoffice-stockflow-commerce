package com.stockflow.design.internal.controller.dto;

import com.stockflow.design.internal.domain.TextStyle;
import java.time.Instant;
import java.util.UUID;

/** A text and its type style in a design project's library (FE request 2026-11-13 E-1). */
public record DesignProjectTextResponse(UUID id, UUID projectId, String text, TextStyle style, Instant createdAt) {
}
