package com.stockflow.product.internal.domain;

import java.util.Objects;
import java.util.UUID;
import com.stockflow.common.storage.StoredFile;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

/** One entry in a product's media gallery (WBS 3.1.1.2). */
public record ProductImage(UUID id, String url, int sortOrder, StoredFile storedFile) {

    public ProductImage(UUID id, String url, int sortOrder) {
        this(id, url, sortOrder, null);
    }

    public ProductImage {
        Objects.requireNonNull(id, "id");
        if (storedFile == null && (url == null || url.isBlank())) {
            throw new IllegalArgumentException("Image url must not be blank");
        }
        if (storedFile != null && (url != null || storedFile.sizeBytes() <= 0)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Stored images require a key and positive size, not a URL");
        }
    }
}
