package com.stockflow.common.storage;

/**
 * What the object store knows about an object without reading its bytes.
 *
 * @param sha256 lower-case hex SHA-256 of the whole object as the store computed it when the object
 *               was written, or null when it was written without one (objects stored before SHA-256
 *               was requested, or copied in by a tool that did not ask for it). Null means "unknown",
 *               never "matches": a caller that needs the digest reads the bytes instead.
 */
public record ObjectStat(long sizeBytes, String contentType, String sha256) {

    public ObjectStat {
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("Size must not be negative");
        }
        if (sha256 != null && !Checksums.isSha256Hex(sha256)) {
            throw new IllegalArgumentException("sha256 must be lower-case hex");
        }
    }
}
