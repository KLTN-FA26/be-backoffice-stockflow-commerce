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
import com.stockflow.procurement.internal.domain.PurchaseOrderAttachmentCategory;
import com.stockflow.procurement.internal.service.PurchaseOrderAttachmentService;
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

/**
 * Documents kept with a purchase order - the supplier's quotation, the contract, the supplier's confirmation, a
 * delivery note: PDF, Word, Excel, or a photo of the paper, at most 25 MB. Sent as multipart, or uploaded straight to
 * storage through {@code /uploads} and named by its upload id (docs/direct-uploads-fe-contract.md).
 */
@RestController
@RequestMapping("/api/v1/purchase-orders/{purchaseOrderId}/attachments")
@Tag(name = "Purchase order documents", description = "Quotations, contracts, confirmations and delivery notes of an order")
class PurchaseOrderAttachmentController {

    /** One document of an order; its bytes come from {@code .../{attachmentId}/download-url}. */
    record AttachmentResponse(UUID id, UUID purchaseOrderId, String fileName, String fileType,
                              PurchaseOrderAttachmentCategory category, Integer sizeBytes, UUID uploadedBy,
                              Instant uploadedAt) {
    }

    record AttachUploadedDocument(@NotNull UUID uploadId, @NotNull PurchaseOrderAttachmentCategory category) {
    }

    record DocumentLink(String url, Instant expiresAt) {
    }

    private final PurchaseOrderAttachmentService attachments;

    PurchaseOrderAttachmentController(PurchaseOrderAttachmentService attachments) {
        this.attachments = attachments;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Keep a document with the order (multipart: file, category)")
    @RequiresPermission(resource = PurchaseOrderResources.PURCHASE_ORDERS, action = Action.UPDATE)
    public ApiResponse<AttachmentResponse> attach(@PathVariable UUID purchaseOrderId,
                                                  @RequestPart("file") MultipartFile file,
                                                  @RequestParam PurchaseOrderAttachmentCategory category,
                                                  @AuthenticatedUser CurrentUser user) {
        try (InputStream content = file.getInputStream()) {
            var upload = new FileUpload(file.getOriginalFilename() == null ? "document" : file.getOriginalFilename(),
                    file.getContentType(), file.getSize(), content);
            return ApiResponse.ok(toResponse(attachments.attach(purchaseOrderId, category,
                    IncomingFiles.sent(List.of(upload)), user.userId())));
        } catch (IOException ex) {
            throw new StorageException("Could not read the multipart upload", ex);
        }
    }

    @PostMapping(path = "/uploads", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Step one of a document uploaded straight to storage: a signed PUT for the declared file")
    @RequiresPermission(resource = PurchaseOrderResources.PURCHASE_ORDERS, action = Action.UPDATE)
    public ResponseEntity<ApiResponse<UploadSlot>> beginUpload(@PathVariable UUID purchaseOrderId,
                                                               @Valid @RequestBody UploadSlotRequest request,
                                                               @AuthenticatedUser CurrentUser user) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(attachments.beginUpload(purchaseOrderId, request, user.userId())));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Keep a document uploaded through /uploads; the same uploadId again answers the same document")
    @RequiresPermission(resource = PurchaseOrderResources.PURCHASE_ORDERS, action = Action.UPDATE)
    public ApiResponse<AttachmentResponse> attachUploaded(@PathVariable UUID purchaseOrderId,
                                                          @Valid @RequestBody AttachUploadedDocument body,
                                                          @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(toResponse(attachments.attach(purchaseOrderId, body.category(),
                IncomingFiles.uploaded(user.userId(), List.of(body.uploadId())), user.userId())));
    }

    @GetMapping
    @Operation(summary = "The order's documents, oldest first")
    @RequiresPermission(resource = PurchaseOrderResources.PURCHASE_ORDERS, action = Action.READ)
    public ApiResponse<List<AttachmentResponse>> list(@PathVariable UUID purchaseOrderId) {
        return ApiResponse.ok(attachments.list(purchaseOrderId).stream().map(PurchaseOrderAttachmentController::toResponse)
                .toList());
    }

    @GetMapping("/{attachmentId}/download-url")
    @Operation(summary = "A short-lived link saving the document under its name; audited")
    @RequiresPermission(resource = PurchaseOrderResources.PURCHASE_ORDERS, action = Action.READ)
    public ResponseEntity<ApiResponse<DocumentLink>> downloadLink(@PathVariable UUID purchaseOrderId,
                                                                  @PathVariable UUID attachmentId) {
        var link = attachments.downloadLink(purchaseOrderId, attachmentId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(new DocumentLink(link.url(), link.expiresAt())));
    }

    private static AttachmentResponse toResponse(PurchaseOrderAttachmentService.AttachmentView view) {
        return new AttachmentResponse(view.id(), view.purchaseOrderId(), view.fileName(), view.fileType(),
                view.category(), view.sizeBytes(), view.uploadedBy(), view.uploadedAt());
    }
}
