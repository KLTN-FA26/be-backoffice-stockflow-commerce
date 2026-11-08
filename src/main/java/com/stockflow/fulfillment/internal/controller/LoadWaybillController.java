package com.stockflow.fulfillment.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.common.storage.FileUpload;
import com.stockflow.common.storage.IncomingFiles;
import com.stockflow.common.storage.StorageException;
import com.stockflow.common.storage.UploadSlot;
import com.stockflow.common.storage.UploadSlotRequest;
import com.stockflow.fulfillment.internal.controller.dto.LoadResponse;
import com.stockflow.fulfillment.internal.controller.dto.WaybillChangeResponse;
import com.stockflow.fulfillment.internal.controller.dto.WaybillFileResponse;
import com.stockflow.fulfillment.internal.controller.dto.WaybillRequest;
import com.stockflow.fulfillment.internal.service.Dispatching;
import com.stockflow.fulfillment.internal.service.LoadWaybillFiles;
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
import org.springframework.web.bind.annotation.PutMapping;
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
 * A load's waybill number, its history and the carrier's waybill files (3-2; kltn-docs 09 §4.4, BR-23, BR-24, BR-28).
 * Recording the number and attaching a file is the dispatcher's loading work ({@code fulfillment-dispatch:UPDATE});
 * correcting the number after the vehicle left also needs {@code fulfillment-waybill-corrections:UPDATE}, which the
 * service checks. Reading is {@code fulfillment-shipments:READ}, as the load is. Customers never see the files.
 */
@RestController
@RequestMapping("/api/v1/fulfillment/loads/{loadId}")
@Tag(name = "Fulfillment - dispatch")
class LoadWaybillController {

    private final Dispatching dispatching;
    private final LoadWaybillFiles files;
    private final DispatchWebMapper mapper;

    LoadWaybillController(Dispatching dispatching, LoadWaybillFiles files, DispatchWebMapper mapper) {
        this.dispatching = dispatching;
        this.files = files;
        this.mapper = mapper;
    }

    @PutMapping("/waybill")
    @Operation(summary = "Record the carrier's waybill number (and the plate, driver when known) on a booked load. After "
            + "the vehicle left: a correction, with a reason and fulfillment-waybill-corrections. 409 "
            + "INVALID_LOAD_TRANSITION (no carrier booked, or cancelled), WAYBILL_DUPLICATE, WAYBILL_FROM_CARRIER_API")
    @RequiresPermission(resource = FulfillmentResources.DISPATCH, action = Action.UPDATE)
    @Auditable(action = AuditAction.UPDATE, resourceType = "dispatch-load", resourceId = "#loadId",
            details = "{waybillNo: #request.waybillNo()}")
    public ApiResponse<LoadResponse> recordWaybill(@PathVariable UUID loadId,
                                                   @Valid @RequestBody WaybillRequest request,
                                                   @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(mapper.toResponse(dispatching.recordWaybill(loadId, new Dispatching.WaybillEntry(
                request.waybillNo(), request.vehiclePlate(), request.driverName(), request.driverPhone(),
                request.reason()), actor)));
    }

    @GetMapping("/waybill-history")
    @Operation(summary = "Every change of the load's waybill number, oldest first")
    @RequiresPermission(resource = FulfillmentResources.SHIPMENTS, action = Action.READ)
    public ApiResponse<List<WaybillChangeResponse>> waybillHistory(@PathVariable UUID loadId) {
        return ApiResponse.ok(dispatching.waybillHistory(loadId).stream().map(c -> new WaybillChangeResponse(
                c.oldWaybillNo(), c.newWaybillNo(), c.source(), c.changedBy(), c.changedByName(), c.changedAt(),
                c.reason())).toList());
    }

    @PostMapping(path = "/waybill-files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Keep the carrier's waybill file with the load (multipart: file; PDF, image, Word or Excel)")
    @RequiresPermission(resource = FulfillmentResources.DISPATCH, action = Action.UPDATE)
    public ApiResponse<WaybillFileResponse> attach(@PathVariable UUID loadId, @RequestPart("file") MultipartFile file,
                                                   @AuthenticatedUser CurrentUser actor) {
        try (InputStream content = file.getInputStream()) {
            var upload = new FileUpload(file.getOriginalFilename() == null ? "waybill" : file.getOriginalFilename(),
                    file.getContentType(), file.getSize(), content);
            return ApiResponse.ok(toResponse(files.attach(loadId, IncomingFiles.sent(List.of(upload)),
                    actor.userId())));
        } catch (IOException ex) {
            throw new StorageException("Could not read the multipart upload", ex);
        }
    }

    @PostMapping(path = "/waybill-files/uploads", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Step one of a waybill file uploaded straight to storage: a signed PUT for the declared file")
    @RequiresPermission(resource = FulfillmentResources.DISPATCH, action = Action.UPDATE)
    public ResponseEntity<ApiResponse<UploadSlot>> beginUpload(@PathVariable UUID loadId,
                                                               @Valid @RequestBody UploadSlotRequest request,
                                                               @AuthenticatedUser CurrentUser actor) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(files.beginUpload(loadId, request, actor.userId())));
    }

    @PostMapping(path = "/waybill-files", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Keep a waybill file uploaded through /uploads; the same uploadId again answers the same file")
    @RequiresPermission(resource = FulfillmentResources.DISPATCH, action = Action.UPDATE)
    public ApiResponse<WaybillFileResponse> attachUploaded(@PathVariable UUID loadId,
                                                           @Valid @RequestBody WaybillFileResponse.Uploaded body,
                                                           @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(toResponse(files.attach(loadId,
                IncomingFiles.uploaded(actor.userId(), List.of(body.uploadId())), actor.userId())));
    }

    @GetMapping("/waybill-files")
    @Operation(summary = "The load's waybill files, oldest first")
    @RequiresPermission(resource = FulfillmentResources.SHIPMENTS, action = Action.READ)
    public ApiResponse<List<WaybillFileResponse>> list(@PathVariable UUID loadId) {
        return ApiResponse.ok(files.list(loadId).stream().map(LoadWaybillController::toResponse).toList());
    }

    @GetMapping("/waybill-files/{fileId}/download-url")
    @Operation(summary = "A short-lived link saving the waybill file under its name; audited")
    @RequiresPermission(resource = FulfillmentResources.SHIPMENTS, action = Action.READ)
    public ResponseEntity<ApiResponse<WaybillFileResponse.Link>> downloadLink(@PathVariable UUID loadId,
                                                                             @PathVariable UUID fileId) {
        var link = files.downloadLink(loadId, fileId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(new WaybillFileResponse.Link(link.url(), link.expiresAt())));
    }

    private static WaybillFileResponse toResponse(LoadWaybillFiles.WaybillFile f) {
        return new WaybillFileResponse(f.id(), f.loadId(), f.fileName(), f.contentType(), f.sizeBytes(),
                f.uploadedBy(), f.uploadedAt());
    }
}
