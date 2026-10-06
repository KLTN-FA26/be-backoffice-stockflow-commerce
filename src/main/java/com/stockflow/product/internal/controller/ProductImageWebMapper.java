package com.stockflow.product.internal.controller;

import com.stockflow.common.storage.DownloadLink;
import com.stockflow.product.internal.controller.dto.ImageDownloadResponse;
import com.stockflow.product.internal.controller.dto.ProductImageResponse;
import com.stockflow.product.internal.domain.ProductImage;
import org.springframework.stereotype.Component;

@Component
class ProductImageWebMapper {
    ProductImageResponse toResponse(ProductImage image) {
        var file = image.storedFile();
        return new ProductImageResponse(image.id(), image.sortOrder(), image.url(),
                file == null ? null : file.originalName(), file == null ? null : file.contentType(),
                file == null ? null : file.sizeBytes(), file == null ? null : file.storedAt(), image.renditions().stream()
                        .map(r -> new ProductImageResponse.Rendition(r.edge(), r.width(), r.height(), r.file().contentType(), r.file().sizeBytes())).toList());
    }

    ImageDownloadResponse toResponse(DownloadLink link) {
        return new ImageDownloadResponse(link.url(), link.expiresAt());
    }
}
