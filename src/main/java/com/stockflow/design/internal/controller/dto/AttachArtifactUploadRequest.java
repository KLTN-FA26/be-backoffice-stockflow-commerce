package com.stockflow.design.internal.controller.dto;

import com.stockflow.design.api.DesignArtifactRole;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Attach a file uploaded straight to the object store ({@code POST .../artifacts/uploads}) as a revision of the draft.
 *
 * @param role             CUSTOMER_PREVIEW when absent, as for a multipart upload
 * @param documentChecksum the SHA-256 of the canonical layout a customer's file was rendered from, or absent
 */
public record AttachArtifactUploadRequest(@NotNull UUID uploadId, DesignArtifactRole role, String documentChecksum) {

    public DesignArtifactRole roleOrDefault() {
        return role == null ? DesignArtifactRole.CUSTOMER_PREVIEW : role;
    }
}
