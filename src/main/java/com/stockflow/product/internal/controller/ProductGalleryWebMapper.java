package com.stockflow.product.internal.controller;

import com.stockflow.product.internal.controller.dto.GalleryItemRequest;
import com.stockflow.product.internal.controller.dto.GalleryItemResponse;
import com.stockflow.product.internal.controller.dto.GalleryResponse;
import com.stockflow.product.internal.controller.dto.PublicGalleryResponse;
import com.stockflow.product.internal.domain.GalleryItem;
import com.stockflow.product.internal.service.ProductGalleryService;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
class ProductGalleryWebMapper {
    GalleryResponse toResponse(ProductGalleryService.GalleryView view) {
        return new GalleryResponse(view.revision(), items(view.working()), items(view.published()),
                view.editedBy(), view.approvedBy());
    }

    PublicGalleryResponse toResponse(ProductGalleryService.PublicGallery gallery) {
        return new PublicGalleryResponse(gallery.productId(), gallery.variantId(), gallery.images().stream()
                .map(image -> new PublicGalleryResponse.Image(image.imageId(), image.caption(),
                        image.renditions().stream().map(r -> new PublicGalleryResponse.Rendition(
                                r.edge(), r.width(), r.height(), r.contentType(), r.url())).toList()))
                .toList());
    }

    List<GalleryItem> toItems(List<GalleryItemRequest> requests) {
        return requests.stream().map(item -> new GalleryItem(item.imageId(), item.caption())).toList();
    }

    private static List<GalleryItemResponse> items(List<GalleryItem> items) {
        return items.stream().map(item -> new GalleryItemResponse(item.imageId(), item.caption())).toList();
    }
}
