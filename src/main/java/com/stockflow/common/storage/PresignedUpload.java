package com.stockflow.common.storage;

import java.util.Map;

/** A signed PUT: the URL, and the headers its signature covers (Host excepted - the browser sets it). */
public record PresignedUpload(String url, Map<String, String> headers) {

    public PresignedUpload {
        headers = Map.copyOf(headers);
    }
}
