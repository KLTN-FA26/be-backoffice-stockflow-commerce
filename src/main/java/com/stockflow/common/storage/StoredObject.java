package com.stockflow.common.storage;

import java.time.Instant;

/** One object as a listing shows it: its key and when it was last written. */
public record StoredObject(String key, Instant lastModified) {
}
