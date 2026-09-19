package com.stockflow.common.storage;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

import java.io.InputStream;
import java.util.Objects;

/** Request-scoped stream; the caller closes it after the storage operation. */
public record FileUpload(String originalName, String contentType, long sizeBytes, InputStream content) {
    public FileUpload {
        Objects.requireNonNull(content, "content");
        if (originalName == null || originalName.isBlank() || originalName.length() > 255 || sizeBytes < 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Invalid upload metadata");
        }
    }
}
