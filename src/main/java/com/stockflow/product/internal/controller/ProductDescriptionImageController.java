package com.stockflow.product.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.common.storage.FileUpload;
import com.stockflow.common.storage.StorageException;
import com.stockflow.product.internal.controller.dto.DescriptionImageResponse;
import com.stockflow.product.internal.service.DescriptionImageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.UUID;

/**
 * Images put inside a product's or a variant's rich-text description (ADR-0013). The answer's URL is public at once,
 * for the editor to show; the description's HTML is what names it.
 */
@RestController
@RequestMapping("/api/v1/products/{productId}/description-images")
@Tag(name = "Product description images", description = "Images inside a product's or a variant's description")
class ProductDescriptionImageController {

    private final DescriptionImageService images;

    ProductDescriptionImageController(DescriptionImageService images) {
        this.images = images;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Upload an image for a description: scanned, resized, public at once on the CDN")
    @RequiresPermission(resource = ProductResources.PRODUCTS, action = Action.UPDATE)
    public ApiResponse<DescriptionImageResponse> upload(@PathVariable UUID productId,
                                                        @RequestPart("file") MultipartFile file,
                                                        @AuthenticatedUser CurrentUser user) {
        try (var content = file.getInputStream()) {
            var upload = new FileUpload(file.getOriginalFilename(), file.getContentType(), file.getSize(), content);
            var image = images.upload(productId, upload, user.userId());
            return ApiResponse.ok(new DescriptionImageResponse(image.url(), image.width(), image.height(),
                    image.renditions().stream().map(r -> new DescriptionImageResponse.Rendition(r.edge(), r.width(),
                            r.height(), r.url())).toList()));
        } catch (IOException ex) {
            throw new StorageException("Could not read the multipart upload", ex);
        }
    }
}
