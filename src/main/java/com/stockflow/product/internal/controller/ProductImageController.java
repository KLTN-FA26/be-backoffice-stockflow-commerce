package com.stockflow.product.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.common.storage.FileUpload;
import com.stockflow.common.storage.StorageException;
import com.stockflow.product.internal.controller.dto.ImageDownloadResponse;
import com.stockflow.product.internal.controller.dto.ProductImageResponse;
import com.stockflow.product.internal.service.ProductImageService;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
@RestController
@RequestMapping("/api/v1/products/{productId}/images")
class ProductImageController {
    private final ProductImageService images;
    private final ProductImageWebMapper mapper;

    ProductImageController(ProductImageService images, ProductImageWebMapper mapper) {
        this.images = images;
        this.mapper = mapper;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(resource = ProductResources.PRODUCTS, action = Action.UPDATE)
    public ApiResponse<ProductImageResponse> upload(@PathVariable UUID productId,
            @RequestPart("file") MultipartFile file,
            @org.springframework.web.bind.annotation.RequestHeader(name = "Idempotency-Key", required = false) String requestKey,
            @AuthenticatedUser Optional<CurrentUser> user) {
        try (var content = file.getInputStream()) {
            var upload = new FileUpload(file.getOriginalFilename(), file.getContentType(), file.getSize(), content);
            return ApiResponse.ok(mapper.toResponse(requestKey == null ? images.upload(productId, upload)
                    : images.upload(productId, upload, user.map(CurrentUser::userId).orElse(null), requestKey)));
        } catch (IOException ex) {
            throw new StorageException("Could not read the multipart upload", ex);
        }
    }

    @GetMapping
    @RequiresPermission(resource = ProductResources.PRODUCTS, action = Action.READ)
    public ApiResponse<List<ProductImageResponse>> list(@PathVariable UUID productId) {
        return ApiResponse.ok(images.list(productId).stream().map(mapper::toResponse).toList());
    }

    @GetMapping("/{imageId}/download-url")
    @RequiresPermission(resource = ProductResources.PRODUCTS, action = Action.READ)
    public ResponseEntity<ApiResponse<ImageDownloadResponse>> downloadLink(@PathVariable UUID productId,
                                                                         @PathVariable UUID imageId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(mapper.toResponse(images.downloadLink(productId, imageId))));
    }
}
