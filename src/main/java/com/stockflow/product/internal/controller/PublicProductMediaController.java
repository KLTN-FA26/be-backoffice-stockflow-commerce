package com.stockflow.product.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.product.internal.service.ProductGalleryService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.UUID;

/** Anonymous storefront projection. It exposes only approved immutable CloudFront URLs. */
@RestController
@RequestMapping("/api/v1/public/products")
class PublicProductMediaController {
    private final ProductGalleryService galleries;
    PublicProductMediaController(ProductGalleryService galleries) { this.galleries = galleries; }

    @GetMapping("/{productId}/gallery")
    public ResponseEntity<ApiResponse<ProductGalleryService.PublicGallery>> gallery(@PathVariable UUID productId,
            @RequestParam(required = false) String sku) {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
                .body(ApiResponse.ok(galleries.publicGallery(productId, sku)));
    }
}
