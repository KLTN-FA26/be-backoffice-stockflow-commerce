package com.stockflow.common.storage;

import java.time.Instant;

/**
 * A file that has been stored: where it is, and enough about it to serve or list it.
 *
 * @param key         the storage key. Opaque to callers - never build one by hand, never parse one.
 *                    {@link FileStorage} owns the layout, which is what lets it change without a
 *                    migration of every reference in the database.
 * @param originalName what the user called it. Kept for downloads and never used as the key: two
 *                     users uploading {@code invoice.pdf} must not overwrite each other, and a
 *                     filename is attacker-controlled.
 * @param contentType  the type the file was accepted as, already checked against
 *                     {@link ContentTypePolicy}
 */
public record StoredFile(
        String key,
        String originalName,
        String contentType,
        long sizeBytes,
        Instant storedAt
) {

    public StoredFile {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("A stored file must have a key");
        }
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("Size must not be negative");
        }
    }
}
