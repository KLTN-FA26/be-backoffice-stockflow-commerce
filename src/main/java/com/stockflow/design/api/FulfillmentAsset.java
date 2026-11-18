package com.stockflow.design.api;

import java.time.Instant;
import java.util.UUID;

/** A short-lived link to one {@link ConfirmedAsset}, issued only once the stored image is still the approved one. */
public record FulfillmentAsset(UUID assetId, String originalName, String contentType, long sizeBytes, String checksum,
                               String url, Instant expiresAt) { }
