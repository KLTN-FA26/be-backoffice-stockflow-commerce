package com.stockflow.common.storage;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Permission to upload one declared file straight to the object store.
 *
 * <p>The browser sends {@code PUT url} with exactly {@code headers} and the file as the body, then
 * names {@code uploadId} in the request that attaches the file to a record. The URL is a short-lived
 * credential: never log it or store it.</p>
 *
 * @param headers the headers the signature covers; the store refuses the upload without them
 */
public record UploadSlot(UUID uploadId, String method, String url, Map<String, String> headers, Instant expiresAt) {

    public UploadSlot {
        headers = Map.copyOf(headers);
    }
}
