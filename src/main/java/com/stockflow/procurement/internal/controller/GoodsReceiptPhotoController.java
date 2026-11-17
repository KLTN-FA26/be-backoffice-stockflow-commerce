package com.stockflow.procurement.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.common.storage.FileUpload;
import com.stockflow.common.storage.IncomingFiles;
import com.stockflow.common.storage.StorageException;
import com.stockflow.common.storage.UploadSlot;
import com.stockflow.common.storage.UploadSlotRequest;
import com.stockflow.procurement.internal.domain.GoodsReceiptPhotoStage;
import com.stockflow.procurement.internal.service.GoodsReceiptPhotoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * Photos of a goods receipt (04b F-RCV step 05): the warehouse's of what arrived, of the delivery as a whole or of one
 * line, while the receipt is counted (or, a historical purchase receipt, awaits its old approval) - evidence when a
 * carton is refused or a delivery disputed.
 * JPEG or PNG, at most 20 MB and {@value GoodsReceiptPhotoService#MAX_PHOTOS_PER_SUBJECT} per subject. Sent as
 * multipart, or uploaded straight to storage through {@code /uploads} and named by its upload id
 * (docs/direct-uploads-fe-contract.md). Kept for good: there is no delete. Photos QC took while receiving QC existed
 * are listed with stage {@code QC}; none are added any more.
 */
@RestController
@RequestMapping("/api/v1/goods-receipts/{receiptId}")
@Tag(name = "Goods receipt photos", description = "Evidence photos taken at receiving")
class GoodsReceiptPhotoController {

    /** One photo; {@code thumbnailUrl} is a short-lived link to a small copy, absent where storage signs none. */
    record PhotoResponse(UUID id, UUID receiptId, UUID receiptLineId, GoodsReceiptPhotoStage stage, String fileName,
                         String contentType, long sizeBytes, UUID takenBy, Instant takenAt, String thumbnailUrl) {
    }

    /** A receiving photo uploaded through {@code /photos/uploads}; {@code receiptLineId} absent for the delivery. */
    record AttachReceivingPhoto(@NotNull UUID uploadId, UUID receiptLineId) {
    }

    record PhotoLink(String url, Instant expiresAt) {
    }

    private final GoodsReceiptPhotoService photos;

    GoodsReceiptPhotoController(GoodsReceiptPhotoService photos) {
        this.photos = photos;
    }

    // ── At receiving: the warehouse ──

    @PostMapping(path = "/photos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Keep a photo of what arrived (multipart: file; receiptLineId for one line, absent for the delivery)")
    @RequiresPermission(resource = GoodsReceiptResources.GOODS_RECEIPTS, action = Action.UPDATE)
    public ApiResponse<PhotoResponse> addReceivingPhoto(@PathVariable UUID receiptId,
                                                        @RequestPart("file") MultipartFile file,
                                                        @RequestParam(required = false) UUID receiptLineId,
                                                        @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(toResponse(sent(file, photo -> photos.add(receiptId, receiptLineId,
                GoodsReceiptPhotoStage.RECEIPT, photo, user.userId()))));
    }

    @PostMapping(path = "/photos/uploads", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Step one of a receiving photo uploaded straight to storage: a signed PUT for the declared file")
    @RequiresPermission(resource = GoodsReceiptResources.GOODS_RECEIPTS, action = Action.UPDATE)
    public ResponseEntity<ApiResponse<UploadSlot>> beginReceivingUpload(@PathVariable UUID receiptId,
            @RequestParam(required = false) UUID receiptLineId, @Valid @RequestBody UploadSlotRequest request,
            @AuthenticatedUser CurrentUser user) {
        return slot(photos.beginUpload(receiptId, receiptLineId, GoodsReceiptPhotoStage.RECEIPT, request,
                user.userId()));
    }

    @PostMapping(path = "/photos", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Keep a receiving photo uploaded through /photos/uploads; the same uploadId again answers the same photo")
    @RequiresPermission(resource = GoodsReceiptResources.GOODS_RECEIPTS, action = Action.UPDATE)
    public ApiResponse<PhotoResponse> attachReceivingPhoto(@PathVariable UUID receiptId,
                                                           @Valid @RequestBody AttachReceivingPhoto body,
                                                           @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(toResponse(photos.add(receiptId, body.receiptLineId(), GoodsReceiptPhotoStage.RECEIPT,
                IncomingFiles.uploaded(user.userId(), List.of(body.uploadId())), user.userId())));
    }

    // ── Reading ──

    @GetMapping("/photos")
    @Operation(summary = "Every photo of the receipt, oldest first, each with a thumbnail link")
    @RequiresPermission(resource = GoodsReceiptResources.GOODS_RECEIPTS, action = Action.READ)
    public ApiResponse<List<PhotoResponse>> list(@PathVariable UUID receiptId) {
        return ApiResponse.ok(photos.list(receiptId).stream().map(GoodsReceiptPhotoController::toResponse).toList());
    }

    @GetMapping("/photos/{photoId}/download-url")
    @Operation(summary = "A five-minute link to one photo: its display copy (no location metadata); original=true saves "
            + "the photo exactly as taken, and is audited")
    @RequiresPermission(resource = GoodsReceiptResources.GOODS_RECEIPTS, action = Action.READ)
    public ResponseEntity<ApiResponse<PhotoLink>> link(@PathVariable UUID receiptId, @PathVariable UUID photoId,
                                                       @RequestParam(defaultValue = "false") boolean original) {
        var link = original ? photos.originalLink(receiptId, photoId) : photos.viewLink(receiptId, photoId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(new PhotoLink(link.url(), link.expiresAt())));
    }

    /** Hands the part to {@code keep} as one sent photo, and closes its stream once the photo is kept or refused. */
    private static GoodsReceiptPhotoService.PhotoView sent(MultipartFile file,
            Function<IncomingFiles, GoodsReceiptPhotoService.PhotoView> keep) {
        String name = file.getOriginalFilename() == null || file.getOriginalFilename().isBlank() ? "photo"
                : file.getOriginalFilename();
        try (InputStream content = file.getInputStream()) {
            return keep.apply(IncomingFiles.sent(List.of(new FileUpload(name, file.getContentType(), file.getSize(),
                    content))));
        } catch (IOException ex) {
            throw new StorageException("Could not read the multipart upload", ex);
        }
    }

    private static ResponseEntity<ApiResponse<UploadSlot>> slot(UploadSlot slot) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(ApiResponse.ok(slot));
    }

    private static PhotoResponse toResponse(GoodsReceiptPhotoService.PhotoView view) {
        return new PhotoResponse(view.id(), view.receiptId(), view.receiptLineId(), view.stage(), view.fileName(),
                view.contentType(), view.sizeBytes(), view.takenBy(), view.takenAt(), view.thumbnailUrl());
    }
}
