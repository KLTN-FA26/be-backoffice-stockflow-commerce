package com.stockflow.product.internal.controller.dto;

import java.time.Instant;

public record ImageDownloadResponse(String url, Instant expiresAt) {
}
