package com.stockflow.product.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.common.storage.FileUpload;
import com.stockflow.common.storage.StorageException;
import com.stockflow.product.internal.controller.dto.DescribeMediaRequest;
import com.stockflow.product.internal.controller.dto.ImageDownloadResponse;
import com.stockflow.product.internal.controller.dto.MediaResponse;
import com.stockflow.product.internal.controller.dto.PublishMediaRequest;
import com.stockflow.product.internal.service.ProductMediaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Images of a variant (decision D5): upload, describe, publish (four-eyes), withdraw, delete. A
 * variant holds at most 20 images, so the list is returned whole.
 */
@RestController
@RequestMapping("/api/v1/products/{productId}/variants/{variantId}/media")
@Tag(name = "Product media", description = "Images of a variant")
class ProductMediaController {

    private final ProductMediaService media;
    private final ProductAdminWebMapper mapper;

    ProductMediaController(ProductMediaService media, ProductAdminWebMapper mapper) {
        this.media = media;
        this.mapper = mapper;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Upload an image: scanned, resized to display renditions, kept private until published")
    @RequiresPermission(resource = ProductResources.PRODUCTS, action = Action.UPDATE)
    public ApiResponse<MediaResponse> upload(@PathVariable UUID productId, @PathVariable UUID variantId,
                                             @RequestPart("file") MultipartFile file,
                                             @RequestHeader(name = "Idempotency-Key", required = false) String requestKey,
                                             @AuthenticatedUser CurrentUser user) {
        try (var content = file.getInputStream()) {
            var upload = new FileUpload(file.getOriginalFilename(), file.getContentType(), file.getSize(), content);
            return ApiResponse.ok(mapper.toResponse(media.upload(productId, variantId, upload, user.userId(), requestKey)));
        } catch (IOException ex) {
            throw new StorageException("Could not read the multipart upload", ex);
        }
    }

    @GetMapping
    @RequiresPermission(resource = ProductResources.PRODUCTS, action = Action.READ)
    public ApiResponse<List<MediaResponse>> list(@PathVariable UUID productId, @PathVariable UUID variantId) {
        return ApiResponse.ok(media.list(productId, variantId).stream().map(mapper::toResponse).toList());
    }

    @GetMapping("/{mediaId}")
    @RequiresPermission(resource = ProductResources.PRODUCTS, action = Action.READ)
    public ApiResponse<MediaResponse> get(@PathVariable UUID productId, @PathVariable UUID variantId,
                                          @PathVariable UUID mediaId) {
        return ApiResponse.ok(mapper.toResponse(media.get(productId, variantId, mediaId)));
    }

    @PutMapping("/{mediaId}")
    @Operation(summary = "Alt text, position, cover image")
    @RequiresPermission(resource = ProductResources.PRODUCTS, action = Action.UPDATE)
    public ApiResponse<MediaResponse> describe(@PathVariable UUID productId, @PathVariable UUID variantId,
                                               @PathVariable UUID mediaId,
                                               @Valid @RequestBody DescribeMediaRequest request) {
        return ApiResponse.ok(mapper.toResponse(media.describe(productId, variantId, mediaId, request.altText(),
                request.sortOrder(), request.primary())));
    }

    @DeleteMapping("/{mediaId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(resource = ProductResources.PRODUCTS, action = Action.UPDATE)
    public void delete(@PathVariable UUID productId, @PathVariable UUID variantId, @PathVariable UUID mediaId) {
        media.delete(productId, variantId, mediaId);
    }

    @PostMapping("/publication")
    @Operation(summary = "Publish images to the storefront; the publisher must not be the uploader")
    @RequiresPermission(resource = ProductResources.PRODUCTS, action = Action.APPROVE)
    public ApiResponse<List<MediaResponse>> publish(@PathVariable UUID productId, @PathVariable UUID variantId,
                                                    @Valid @RequestBody PublishMediaRequest request,
                                                    @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(media.publish(productId, variantId, request.mediaIds(), user.userId()).stream()
                .map(mapper::toResponse).toList());
    }

    @PostMapping("/{mediaId}/withdrawal")
    @Operation(summary = "Take an image off the storefront")
    @RequiresPermission(resource = ProductResources.PRODUCTS, action = Action.APPROVE)
    public ApiResponse<MediaResponse> withdraw(@PathVariable UUID productId, @PathVariable UUID variantId,
                                               @PathVariable UUID mediaId) {
        return ApiResponse.ok(mapper.toResponse(media.withdraw(productId, variantId, mediaId)));
    }

    @GetMapping("/{mediaId}/download-url")
    @RequiresPermission(resource = ProductResources.PRODUCTS, action = Action.READ)
    public ResponseEntity<ApiResponse<ImageDownloadResponse>> downloadLink(@PathVariable UUID productId,
                                                                         @PathVariable UUID variantId,
                                                                         @PathVariable UUID mediaId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(mapper.toResponse(media.downloadLink(productId, variantId, mediaId))));
    }

    @GetMapping("/{mediaId}/renditions/{edge}/download-url")
    @RequiresPermission(resource = ProductResources.PRODUCTS, action = Action.READ)
    public ResponseEntity<ApiResponse<ImageDownloadResponse>> renditionLink(@PathVariable UUID productId,
                                                                          @PathVariable UUID variantId,
                                                                          @PathVariable UUID mediaId,
                                                                          @PathVariable int edge) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(mapper.toResponse(media.renditionLink(productId, variantId, mediaId, edge))));
    }
}
