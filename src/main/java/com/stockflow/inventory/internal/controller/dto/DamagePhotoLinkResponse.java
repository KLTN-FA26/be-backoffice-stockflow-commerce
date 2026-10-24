package com.stockflow.inventory.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/** A short-lived link to one damage photo, fetched by the browser from object storage directly. Never stored. */
@Schema(name = "DamagePhotoLink")
public record DamagePhotoLinkResponse(String url, Instant expiresAt) {
}
