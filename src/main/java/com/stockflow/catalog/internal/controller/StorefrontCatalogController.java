package com.stockflow.catalog.internal.controller;

import com.stockflow.catalog.internal.controller.dto.*;
import com.stockflow.catalog.internal.service.CatalogCommerceService;
import com.stockflow.catalog.internal.service.CatalogSearchService;
import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.api.PageResponse;

import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** B2B catalog content; the security chain requires a signed-in customer or staff member. */
@RestController
@RequestMapping("/api/v1/catalog/products")
class StorefrontCatalogController {
    private final CatalogCommerceService service;
    private final CatalogSearchService search;
    private final CatalogCommerceWebMapper mapper;

    StorefrontCatalogController(
            CatalogCommerceService service, CatalogSearchService search, CatalogCommerceWebMapper mapper) {
        this.service = service;
        this.search = search;
        this.mapper = mapper;
    }

    /**
     * The published catalogue, searched when {@code q} is given (SCRUM-170/181/182). {@code sort} is
     * {@code relevance} (default with {@code q}), {@code newest} (default without) or {@code title};
     * anything else, price included until SCRUM-428, is {@code UNSUPPORTED_PARAMETER}. No-store: the
     * answer sits behind a token and the in-stock order follows live stock.
     */
    @GetMapping
    @Operation(summary = "List or search the published catalogue")
    public ResponseEntity<ApiResponse<PageResponse<CatalogCardResponse>>> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(search.search(q, sort, page, size).map(mapper::toResponse)));
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
