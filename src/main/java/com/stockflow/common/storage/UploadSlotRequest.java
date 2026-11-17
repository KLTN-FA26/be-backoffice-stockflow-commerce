package com.stockflow.common.storage;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * What a browser declares before it uploads a file straight to the object store.
 *
 * <p>Everything here is checked again against the stored object before it is attached to anything:
 * the declaration only lets the backend refuse early and sign a URL that accepts exactly this file.</p>
 *
 * @param sha256 lower-case hex SHA-256 of the file, computed by the browser ({@code crypto.subtle.digest}).
 *               The signed URL carries it, so the store refuses any other bytes.
 */
public record UploadSlotRequest(
        @NotBlank @Size(max = 255) String fileName,
        @NotBlank @Size(max = 100) String contentType,
        @Positive long sizeBytes,
        @NotBlank @Pattern(regexp = "[0-9a-f]{64}", message = "must be a lower-case hex SHA-256")
        @Schema(example = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08") String sha256
) {
}
