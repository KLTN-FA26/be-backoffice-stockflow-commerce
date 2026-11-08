package com.stockflow.design.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.ratelimit.RateLimit;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.common.storage.AttachUploadRequest;
import com.stockflow.common.storage.FileUpload;
import com.stockflow.common.storage.StorageException;
import com.stockflow.common.storage.UploadSlot;
import com.stockflow.common.storage.UploadSlotRequest;
import com.stockflow.design.internal.controller.dto.ArtifactDownloadResponse;
import com.stockflow.design.internal.controller.dto.DesignAssetResponse;
import com.stockflow.design.internal.service.DesignAssetService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * The images a customer uploads into their design (the storefront's P4; kltn-docs 12 BR-04): through the backend, or
 * straight to the object store with a slot and an attach. Handlers are public so the permission proxy advises them; the
 * resource is declared once, on {@code DesignController}.
 */
@RestController
@RequestMapping("/api/v1/designs/{designId}/assets")
@Tag(name = "Design images", description = "Images a customer places in their design")
class DesignAssetController {

    private final DesignAssetService assets;

    DesignAssetController(DesignAssetService assets) {
        this.assets = assets;
    }

    /**
     * Each upload is spooled, scanned and stored, and images are never deleted: a user gets as many an hour as the
     * direct path's slots ({@code stockflow.storage.staged-upload.per-user-per-hour}).
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Upload an image into a draft (multipart part 'file'); retry with the same Idempotency-Key")
    @RequiresPermission(resource = DesignResources.DESIGNS, action = Action.UPDATE)
    @RateLimit(limit = 120, perSeconds = 3600, key = RateLimit.Key.USER, bucket = "design-uploads")
    public ApiResponse<DesignAssetResponse> upload(@PathVariable UUID designId, @AuthenticatedUser CurrentUser user,
            @RequestPart("file") MultipartFile file,
            @RequestHeader(name = "Idempotency-Key", required = false) String requestKey) {
        try (var content = file.getInputStream()) {
            var upload = new FileUpload(file.getOriginalFilename(), file.getContentType(), file.getSize(), content);
            return ApiResponse.ok(toResponse(assets.upload(designId, actor(user), upload, requestKey)));
        } catch (IOException ex) {
            throw new StorageException("Could not read the multipart upload", ex);
        }
    }

    /**
     * Step one of an upload straight to the object store: a signed PUT for exactly the declared image. The response
     * carries a credential, so it is never cached.
     */
    @PostMapping(path = "/uploads", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "A signed PUT to upload an image of the draft straight to storage")
    @RequiresPermission(resource = DesignResources.DESIGNS, action = Action.UPDATE)
    public ResponseEntity<ApiResponse<UploadSlot>> beginUpload(@PathVariable UUID designId,
            @AuthenticatedUser CurrentUser user, @Valid @RequestBody UploadSlotRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(assets.beginUpload(designId, actor(user), request)));
    }

    /** Step two: record the uploaded image. Retrying with the same upload answers with the same image. */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Record an image uploaded with a slot")
    @RequiresPermission(resource = DesignResources.DESIGNS, action = Action.UPDATE)
    public ApiResponse<DesignAssetResponse> attachUpload(@PathVariable UUID designId,
            @AuthenticatedUser CurrentUser user, @Valid @RequestBody AttachUploadRequest request) {
        return ApiResponse.ok(toResponse(assets.attachUpload(designId, actor(user), request.uploadId())));
    }

    @GetMapping
    @Operation(summary = "The design's images, newest first")
    @RequiresPermission(resource = DesignResources.DESIGNS, action = Action.READ)
    public ApiResponse<List<DesignAssetResponse>> list(@PathVariable UUID designId,
                                                      @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(assets.list(designId, actor(user)).stream().map(DesignAssetController::toResponse)
                .toList());
    }

    /** A signed link: never cached, never logged. */
    @GetMapping("/{assetId}/download-url")
    @Operation(summary = "A short-lived link to draw the image from")
    @RequiresPermission(resource = DesignResources.DESIGNS, action = Action.READ)
    public ResponseEntity<ApiResponse<ArtifactDownloadResponse>> link(@PathVariable UUID designId,
            @PathVariable UUID assetId, @AuthenticatedUser CurrentUser user) {
        var link = assets.link(designId, assetId, actor(user));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(new ArtifactDownloadResponse(link.url(), link.expiresAt())));
    }

    private static DesignAssetResponse toResponse(DesignAssetService.AssetView view) {
        return new DesignAssetResponse(view.assetId(), view.designId(), view.originalName(), view.contentType(),
                view.sizeBytes(), view.checksum(), view.widthPx(), view.heightPx(), view.storedAt());
    }

    private static UUID actor(CurrentUser user) {
        return user == null ? null : user.userId();
    }
}
