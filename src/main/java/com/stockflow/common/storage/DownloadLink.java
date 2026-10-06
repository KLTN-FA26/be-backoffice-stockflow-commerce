package com.stockflow.common.storage;

import java.time.Instant;

/** Short-lived capability; never persist or log the URL. */
public record DownloadLink(String url, Instant expiresAt) {
}
