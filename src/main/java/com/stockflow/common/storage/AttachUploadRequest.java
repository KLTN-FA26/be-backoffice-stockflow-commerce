package com.stockflow.common.storage;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Attach a file uploaded straight to the object store: the {@code uploadId} its slot was given
 * ({@link UploadSlot}). Sending it again answers with what the first attach made.
 */
public record AttachUploadRequest(@NotNull UUID uploadId) {
}
