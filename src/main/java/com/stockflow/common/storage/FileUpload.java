package com.stockflow.common.storage;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

import java.io.InputStream;
import java.util.Objects;

/**
 * Request-scoped stream; the caller closes it after the storage operation.
 *
 * @param sha256 the SHA-256 of {@code content}, lower-case hex, when the caller has already computed
 *               it over the same bytes ({@link BufferedUpload#checksum()}); null otherwise. The store
 *               refuses the write when the bytes it receives hash differently.
 */
public record FileUpload(String originalName, String contentType, long sizeBytes, InputStream content,
                         String sha256) {
    public FileUpload {
        Objects.requireNonNull(content, "content");
        if (originalName == null || originalName.isBlank() || originalName.length() > 255 || sizeBytes < 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Invalid upload metadata");
        }
        if (sha256 != null && !Checksums.isSha256Hex(sha256)) {
            throw new IllegalArgumentException("sha256 must be lower-case hex");
        }
    }

    /** An upload whose digest nobody has computed yet. */
    public FileUpload(String originalName, String contentType, long sizeBytes, InputStream content) {
        this(originalName, contentType, sizeBytes, content, null);
    }
}
