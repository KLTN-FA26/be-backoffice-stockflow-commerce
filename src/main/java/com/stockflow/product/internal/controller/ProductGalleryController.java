package com.stockflow.product.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.common.storage.DownloadLink;
import com.stockflow.product.internal.domain.GalleryItem;
import com.stockflow.product.internal.service.ProductGalleryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/products/{productId}")
class ProductGalleryController {
    private final ProductGalleryService service;
    ProductGalleryController(ProductGalleryService service) { this.service = service; }
    record EditRequest(@PositiveOrZero long revision, @NotNull @Size(max = 20) List<@NotNull GalleryItem> items) { }
    record ApproveRequest(@PositiveOrZero long revision) { }
    @GetMapping("/gallery")
    @RequiresPermission(resource = ProductResources.PRODUCTS, action = Action.READ)
    public ApiResponse<ProductGalleryService.GalleryView> get(@PathVariable UUID productId) {
        return ApiResponse.ok(service.get(productId));
    }
    @PutMapping("/gallery")
    @RequiresPermission(resource = ProductResources.PRODUCTS, action = Action.UPDATE)
    public ApiResponse<ProductGalleryService.GalleryView> edit(@PathVariable UUID productId,
            @Valid @RequestBody EditRequest request, @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(service.edit(productId, user == null ? null : user.userId(), request.revision(), request.items()));
    }
    @PostMapping("/gallery/approval")
    @RequiresPermission(resource = ProductResources.PRODUCTS, action = Action.APPROVE)
    public ApiResponse<ProductGalleryService.GalleryView> approve(@PathVariable UUID productId,
            @Valid @RequestBody ApproveRequest request, @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(service.approve(productId, user == null ? null : user.userId(), request.revision()));
    }
    @GetMapping("/images/{imageId}/renditions/{edge}/download-url")
    @RequiresPermission(resource = ProductResources.PRODUCTS, action = Action.READ)
    public ResponseEntity<ApiResponse<DownloadLink>> download(@PathVariable UUID productId,
            @PathVariable UUID imageId, @PathVariable int edge) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(service.rendition(productId, imageId, edge)));
    }

    @GetMapping("/variants/{variantId}/gallery")
    @RequiresPermission(resource = ProductResources.PRODUCTS, action = Action.READ)
    public ApiResponse<ProductGalleryService.GalleryView> getVariant(@PathVariable UUID productId,
            @PathVariable UUID variantId) {
        return ApiResponse.ok(service.getVariant(productId, variantId));
    }

    @PutMapping("/variants/{variantId}/gallery")
    @RequiresPermission(resource = ProductResources.PRODUCTS, action = Action.UPDATE)
    public ApiResponse<ProductGalleryService.GalleryView> editVariant(@PathVariable UUID productId,
            @PathVariable UUID variantId, @Valid @RequestBody EditRequest request,
            @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(service.editVariant(productId, variantId, user.userId(), request.revision(), request.items()));
    }

    @PostMapping("/variants/{variantId}/gallery/approval")
    @RequiresPermission(resource = ProductResources.PRODUCTS, action = Action.APPROVE)
    public ApiResponse<ProductGalleryService.GalleryView> approveVariant(@PathVariable UUID productId,
            @PathVariable UUID variantId, @Valid @RequestBody ApproveRequest request,
            @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(service.approveVariant(productId, variantId, user.userId(), request.revision()));
    }
}
