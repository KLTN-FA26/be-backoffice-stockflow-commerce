package com.stockflow.design.internal.controller.dto;

import java.time.Instant;

public record ArtifactDownloadResponse(String url, Instant expiresAt) {
}
