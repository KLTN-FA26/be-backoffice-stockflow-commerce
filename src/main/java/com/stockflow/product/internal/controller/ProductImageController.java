package com.stockflow.product.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.common.storage.FileUpload;
import com.stockflow.common.storage.StorageException;
import com.stockflow.product.internal.controller.dto.DescribeProductImageRequest;
import com.stockflow.product.internal.controller.dto.ProductImageResponse;
import com.stockflow.product.internal.service.ProductImageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.UUID;

/**
 * The product's own image (ADR-0012): one per product, shown on the storefront's lists and category pages as soon as
 * the product is published - no second person. A variant's images, many per variant and published by hand, are
 * {@link ProductMediaController}'s.
 */
@RestController
@RequestMapping("/api/v1/products/{productId}/image")
@Tag(name = "Product image", description = "The product's own image, one per product")
class ProductImageController {

    private final ProductImageService images;
    private final ProductAdminWebMapper mapper;

    ProductImageController(ProductImageService images, ProductAdminWebMapper mapper) {
        this.images = images;
        this.mapper = mapper;
    }

    @PutMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload the product's image, replacing the one it had: scanned, resized to display renditions")
    @RequiresPermission(resource = ProductResources.PRODUCTS, action = Action.UPDATE)
    public ApiResponse<ProductImageResponse> upload(@PathVariable UUID productId,
                                                    @RequestPart("file") MultipartFile file,
                                                    @RequestParam(name = "altText", required = false) String altText,
                                                    @AuthenticatedUser CurrentUser user) {
        try (var content = file.getInputStream()) {
            var upload = new FileUpload(file.getOriginalFilename(), file.getContentType(), file.getSize(), content);
            return ApiResponse.ok(mapper.toResponse(images.upload(productId, upload, altText, user.userId())));
        } catch (IOException ex) {
            throw new StorageException("Could not read the multipart upload", ex);
        }
    }

    @GetMapping
    @Operation(summary = "The product's image, with a short-lived link showing each rendition; 404 when it has none")
    @RequiresPermission(resource = ProductResources.PRODUCTS, action = Action.READ)
    public ApiResponse<ProductImageResponse> get(@PathVariable UUID productId) {
        return ApiResponse.ok(images.get(productId).map(mapper::toResponse)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEDIA_NOT_FOUND,
                        "Product %s has no image".formatted(productId))));
    }

    @PatchMapping
    @Operation(summary = "The alt text of the product's image")
    @RequiresPermission(resource = ProductResources.PRODUCTS, action = Action.UPDATE)
    public ApiResponse<ProductImageResponse> describe(@PathVariable UUID productId,
                                                      @Valid @RequestBody DescribeProductImageRequest request) {
        return ApiResponse.ok(mapper.toResponse(images.describe(productId, request.altText())));
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove the product's image; its storefront cards then show its variants' lead image")
    @RequiresPermission(resource = ProductResources.PRODUCTS, action = Action.UPDATE)
    public void delete(@PathVariable UUID productId) {
        images.delete(productId);
    }
}
