package com.stockflow.product.internal.domain;

import java.util.UUID;

/** First item is the cover; captions are plain text and must be escaped by clients. */
public record GalleryItem(UUID imageId, String caption) {
    public GalleryItem {
        if (imageId == null || caption == null || caption.length() > 500) {
            throw new com.stockflow.common.error.BusinessException(com.stockflow.common.error.ErrorCode.VALIDATION_FAILED,
                    "Gallery items need an image id and a caption up to 500 characters");
        }
    }
}
