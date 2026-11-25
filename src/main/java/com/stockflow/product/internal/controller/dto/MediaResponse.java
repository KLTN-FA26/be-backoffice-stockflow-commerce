package com.stockflow.product.internal.controller.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One image of a variant. The original is private: {@code download-url} gives a short-lived link.
 * {@code url} is set only for an image carried over as an external URL.
 *
 * <p>{@code uploadedBy} is the row's audit column, the uploader's username (their id when the account had none,
 * {@code "system"} for a job), while {@code publishedBy} is an id; both stay as they are for the screens that read
 * them. {@code uploadedByName} and {@code publishedByName} are the names to show.</p>
 *
 * @param uploadedByName  the uploader's display name: the full name when {@code uploadedBy} holds an account id;
 *                        otherwise {@code uploadedBy} itself (the username, which is also what a display name falls
 *                        back to). Null only when {@code uploadedBy} is
 * @param publishedByName the publisher's display name (full name, else username); null when unpublished or unknown
 */
public record MediaResponse(UUID mediaId, UUID variantId, String kind, String url, String originalName,
                            String contentType, Long sizeBytes, Instant storedAt, List<Rendition> renditions,
                            String altText, int sortOrder, boolean primary, boolean published, Instant publishedAt,
                            UUID publishedBy, String uploadedBy, Instant uploadedAt, long version,
                            String uploadedByName, String publishedByName) {

    /** @param url a short-lived link showing the rendition, on the list only (null elsewhere) */
    public record Rendition(int edge, int width, int height, String contentType, long sizeBytes, String url) {
    }
}
