package com.stockflow.design.internal.controller.dto;

import com.fasterxml.jackson.annotation.JsonRawValue;
import java.time.Instant;
import java.util.UUID;

/**
 * A design's layout (the storefront's P3). {@code document} is the stored canonical JSON written into the response as
 * it is - not re-serialised - so the client reads back exactly the text {@code checksum} is the SHA-256 of.
 * {@code version} is the design's, to quote on the next save.
 */
public record DesignDocumentResponse(UUID designId, long version, @JsonRawValue String document, String checksum,
                                     Instant updatedAt) {
}
