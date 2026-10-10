package com.stockflow.product.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.product.internal.controller.dto.PublicGalleryResponse;
import com.stockflow.product.internal.service.ProductMediaService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.UUID;

/** Anonymous storefront projection. It exposes only published images and their CDN URLs. */
@RestController
@RequestMapping("/api/v1/public/products")
class PublicProductMediaController {

    private final ProductMediaService media;
    private final ProductAdminWebMapper mapper;

    PublicProductMediaController(ProductMediaService media, ProductAdminWebMapper mapper) {
        this.media = media;
        this.mapper = mapper;
    }

    /** {@code sku} picks a variant; without it, the default variant. */
    @GetMapping("/{productId}/gallery")
    public ResponseEntity<ApiResponse<PublicGalleryResponse>> gallery(@PathVariable UUID productId,
                                                                    @RequestParam(required = false) String sku) {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
                .body(ApiResponse.ok(mapper.toResponse(media.publicGallery(productId, sku))));
    }
}
