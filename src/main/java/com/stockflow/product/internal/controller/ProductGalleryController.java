package com.stockflow.product.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.product.internal.controller.dto.ApproveGalleryRequest;
import com.stockflow.product.internal.controller.dto.EditGalleryRequest;
import com.stockflow.product.internal.controller.dto.GalleryResponse;
import com.stockflow.product.internal.controller.dto.ImageDownloadResponse;
import com.stockflow.product.internal.service.ProductGalleryService;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/products/{productId}")
class ProductGalleryController {
    private final ProductGalleryService service;
    private final ProductGalleryWebMapper mapper;
    private final ProductImageWebMapper imageMapper;
    ProductGalleryController(ProductGalleryService service, ProductGalleryWebMapper mapper,
                             ProductImageWebMapper imageMapper) {
        this.service = service;
        this.mapper = mapper;
        this.imageMapper = imageMapper;
    }
    @GetMapping("/gallery")
    @RequiresPermission(resource = ProductResources.PRODUCTS, action = Action.READ)
    public ApiResponse<GalleryResponse> get(@PathVariable UUID productId) {
        return ApiResponse.ok(mapper.toResponse(service.get(productId)));
    }
    @PutMapping("/gallery")
    @RequiresPermission(resource = ProductResources.PRODUCTS, action = Action.UPDATE)
    public ApiResponse<GalleryResponse> edit(@PathVariable UUID productId,
            @Valid @RequestBody EditGalleryRequest request, @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(mapper.toResponse(service.edit(productId, user == null ? null : user.userId(),
                request.revision(), mapper.toItems(request.items()))));
    }
    @PostMapping("/gallery/approval")
    @RequiresPermission(resource = ProductResources.PRODUCTS, action = Action.APPROVE)
    public ApiResponse<GalleryResponse> approve(@PathVariable UUID productId,
            @Valid @RequestBody ApproveGalleryRequest request, @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(mapper.toResponse(service.approve(productId, user == null ? null : user.userId(), request.revision())));
    }
    @GetMapping("/images/{imageId}/renditions/{edge}/download-url")
    @RequiresPermission(resource = ProductResources.PRODUCTS, action = Action.READ)
    public ResponseEntity<ApiResponse<ImageDownloadResponse>> download(@PathVariable UUID productId,
            @PathVariable UUID imageId, @PathVariable int edge) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(imageMapper.toResponse(service.rendition(productId, imageId, edge))));
    }

    @GetMapping("/variants/{variantId}/gallery")
    @RequiresPermission(resource = ProductResources.PRODUCTS, action = Action.READ)
    public ApiResponse<GalleryResponse> getVariant(@PathVariable UUID productId,
            @PathVariable UUID variantId) {
        return ApiResponse.ok(mapper.toResponse(service.getVariant(productId, variantId)));
    }

    @PutMapping("/variants/{variantId}/gallery")
    @RequiresPermission(resource = ProductResources.PRODUCTS, action = Action.UPDATE)
    public ApiResponse<GalleryResponse> editVariant(@PathVariable UUID productId,
            @PathVariable UUID variantId, @Valid @RequestBody EditGalleryRequest request,
            @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(mapper.toResponse(service.editVariant(productId, variantId, user.userId(),
                request.revision(), mapper.toItems(request.items()))));
    }

    @PostMapping("/variants/{variantId}/gallery/approval")
    @RequiresPermission(resource = ProductResources.PRODUCTS, action = Action.APPROVE)
    public ApiResponse<GalleryResponse> approveVariant(@PathVariable UUID productId,
            @PathVariable UUID variantId, @Valid @RequestBody ApproveGalleryRequest request,
            @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(mapper.toResponse(service.approveVariant(productId, variantId, user.userId(), request.revision())));
    }
}
