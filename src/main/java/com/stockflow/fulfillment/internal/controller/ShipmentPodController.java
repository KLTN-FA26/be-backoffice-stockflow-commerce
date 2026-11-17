package com.stockflow.fulfillment.internal.controller;

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
import com.stockflow.fulfillment.internal.controller.dto.PodFileResponse;
import com.stockflow.fulfillment.internal.controller.dto.WaybillFileResponse;
import com.stockflow.fulfillment.internal.service.ShipmentPodFiles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;

/**
 * Proofs of delivery kept with a shipment (3-6; kltn-docs 09 §4.3 step 2, BR-28, E16: optional; customers never see
 * them). Kept by whoever records the shipment's outcome, {@code fulfillment-delivery-outcomes:UPDATE}; read with
 * {@code fulfillment-shipments:READ}.
 */
@RestController
@RequestMapping("/api/v1/fulfillment/shipments/{shipmentId}/pod-files")
@Tag(name = "Fulfillment - dispatch")
class ShipmentPodController {

    private final ShipmentPodFiles files;

    ShipmentPodController(ShipmentPodFiles files) {
        this.files = files;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Keep a proof of delivery with a delivered shipment (multipart: file; PDF or image)")
    @RequiresPermission(resource = FulfillmentResources.DELIVERY_OUTCOMES, action = Action.UPDATE)
    public ApiResponse<PodFileResponse> attach(@PathVariable UUID shipmentId, @RequestPart("file") MultipartFile file,
                                                   @AuthenticatedUser CurrentUser actor) {
        try (InputStream content = file.getInputStream()) {
            var upload = new FileUpload(file.getOriginalFilename() == null ? "pod" : file.getOriginalFilename(),
                    file.getContentType(), file.getSize(), content);
            return ApiResponse.ok(toResponse(files.attach(shipmentId, IncomingFiles.sent(List.of(upload)),
                    actor.userId())));
        } catch (IOException ex) {
            throw new StorageException("Could not read the multipart upload", ex);
        }
    }

    @PostMapping(path = "/uploads", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Step one of a proof of delivery uploaded straight to storage: a signed PUT for the declared file")
    @RequiresPermission(resource = FulfillmentResources.DELIVERY_OUTCOMES, action = Action.UPDATE)
    public ResponseEntity<ApiResponse<UploadSlot>> beginUpload(@PathVariable UUID shipmentId,
                                                               @Valid @RequestBody UploadSlotRequest request,
                                                               @AuthenticatedUser CurrentUser actor) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(files.beginUpload(shipmentId, request, actor.userId())));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Keep a proof of delivery uploaded through /uploads; the same uploadId again answers the same file")
    @RequiresPermission(resource = FulfillmentResources.DELIVERY_OUTCOMES, action = Action.UPDATE)
    public ApiResponse<PodFileResponse> attachUploaded(@PathVariable UUID shipmentId,
                                                           @Valid @RequestBody WaybillFileResponse.Uploaded body,
                                                           @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(toResponse(files.attach(shipmentId,
                IncomingFiles.uploaded(actor.userId(), List.of(body.uploadId())), actor.userId())));
    }

    @GetMapping
    @Operation(summary = "The shipment's proofs of delivery, oldest first")
    @RequiresPermission(resource = FulfillmentResources.SHIPMENTS, action = Action.READ)
    public ApiResponse<List<PodFileResponse>> list(@PathVariable UUID shipmentId) {
        return ApiResponse.ok(files.list(shipmentId).stream().map(ShipmentPodController::toResponse).toList());
    }

    @GetMapping("/{fileId}/download-url")
    @Operation(summary = "A short-lived link saving the proof of delivery under its name; audited")
    @RequiresPermission(resource = FulfillmentResources.SHIPMENTS, action = Action.READ)
    public ResponseEntity<ApiResponse<WaybillFileResponse.Link>> downloadLink(@PathVariable UUID shipmentId,
                                                                             @PathVariable UUID fileId) {
        var link = files.downloadLink(shipmentId, fileId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(new WaybillFileResponse.Link(link.url(), link.expiresAt())));
    }

    private static PodFileResponse toResponse(ShipmentPodFiles.PodFile f) {
        return new PodFileResponse(f.id(), f.shipmentId(), f.fileName(), f.contentType(), f.sizeBytes(),
                f.uploadedBy(), f.uploadedAt());
    }
}
