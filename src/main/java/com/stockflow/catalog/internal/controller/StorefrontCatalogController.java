package com.stockflow.catalog.internal.controller;

import com.stockflow.catalog.internal.controller.dto.*;
import com.stockflow.catalog.internal.service.CatalogCommerceService;
import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.api.PageResponse;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** B2B catalog content; the security chain requires a signed-in customer or staff member. */
@RestController
@RequestMapping("/api/v1/catalog/products")
class StorefrontCatalogController {
    private final CatalogCommerceService service;
    private final CatalogCommerceWebMapper mapper;

    StorefrontCatalogController(CatalogCommerceService service, CatalogCommerceWebMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<CatalogCardResponse>>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(service.list(page, size).map(mapper::toResponse)));
    }

    @GetMapping("/{slug}")
    public ResponseEntity<ApiResponse<CatalogProductResponse>> detail(@PathVariable String slug) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(mapper.toResponse(service.detail(slug))));
    }

    @GetMapping("/{slug}/gallery")
    public ResponseEntity<ApiResponse<CatalogGalleryResponse>> gallery(@PathVariable String slug) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(mapper.toGalleryResponse(service.gallery(slug))));
    }
}
