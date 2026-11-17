package com.stockflow.fulfillment.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.common.storage.StorageException;
import com.stockflow.fulfillment.internal.controller.dto.WaybillImportResponse;
import com.stockflow.fulfillment.internal.domain.WaybillImportFile;
import com.stockflow.fulfillment.internal.service.WaybillImports;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Waybill numbers imported from the system's file template (3-3; kltn-docs 09 §4.4 "Nhập hàng loạt từ file", BR-25,
 * E18, QD-17). {@code fulfillment-waybill-imports:READ} for the template and past imports; {@code :CREATE} to preview
 * and import a file. Every line is limited to the caller's warehouses: a load of another warehouse is a refused line,
 * not a refused file.
 */
@RestController
@RequestMapping("/api/v1/fulfillment/waybill-imports")
@Tag(name = "Fulfillment - dispatch")
class WaybillImportController {

    private final WaybillImports imports;

    WaybillImportController(WaybillImports imports) {
        this.imports = imports;
    }

    @GetMapping("/template")
    @Operation(summary = "The import template: CSV UTF-8 with a byte-order mark; load code, waybill number, plate, "
            + "driver name, driver phone")
    @RequiresPermission(resource = FulfillmentResources.WAYBILL_IMPORTS, action = Action.READ)
    public ResponseEntity<byte[]> template() {
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("waybill-import-template.csv").build().toString())
                .cacheControl(CacheControl.noStore())
                .body(imports.template());
    }

    @PostMapping(path = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Check each line of a file against the loads it names; nothing is written. 400 "
            + "WAYBILL_IMPORT_INVALID_FILE")
    @RequiresPermission(resource = FulfillmentResources.WAYBILL_IMPORTS, action = Action.CREATE)
    public ApiResponse<WaybillImportResponse> preview(@RequestPart("file") MultipartFile file,
                                                      @AuthenticatedUser CurrentUser actor) {
        WaybillImports.Preview preview = imports.preview(read(file), actor);
        return ApiResponse.ok(new WaybillImportResponse(null, file.getOriginalFilename(), actor.userId(), null,
                preview.rowsTotal(), preview.rowsApplicable(), preview.rowsRejected(),
                preview.lines().stream().map(WaybillImportController::toResponse).toList()));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Import a file: its valid lines are applied, the others kept with their reason, the file kept "
            + "with every load a line changed. A line overwriting a load's number applies only when its line number is "
            + "in overwriteLines (the overwrites the preview showed and the dispatcher confirmed). 400 "
            + "WAYBILL_IMPORT_INVALID_FILE")
    @RequiresPermission(resource = FulfillmentResources.WAYBILL_IMPORTS, action = Action.CREATE)
    public ApiResponse<WaybillImportResponse> importFile(@RequestPart("file") MultipartFile file,
            @RequestParam(required = false) java.util.Set<Integer> overwriteLines,
            @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(toResponse(imports.importFile(read(file), file.getOriginalFilename(), overwriteLines,
                actor)));
    }

    @GetMapping
    @Operation(summary = "Imports the caller made, and those with a line on a load of their warehouses; newest first")
    @RequiresPermission(resource = FulfillmentResources.WAYBILL_IMPORTS, action = Action.READ)
    public ApiResponse<PageResponse<WaybillImportResponse.Row>> list(@RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size, @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(imports.list(page, size == null ? Pages.DEFAULT_PAGE_SIZE : size, actor)
                .map(r -> new WaybillImportResponse.Row(r.id(), r.fileName(), r.uploadedBy(), r.uploadedAt(),
                        r.rowsTotal(), r.rowsApplied(), r.rowsRejected())));
    }

    @GetMapping("/{importId}")
    @Operation(summary = "An import and its lines; another's import shows only its lines on the caller's warehouses")
    @RequiresPermission(resource = FulfillmentResources.WAYBILL_IMPORTS, action = Action.READ)
    public ApiResponse<WaybillImportResponse> get(@PathVariable UUID importId, @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(toResponse(imports.get(importId, actor)));
    }

    /** The file's bytes, refused past the template's limit before it is read whole. */
    private static byte[] read(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.WAYBILL_IMPORT_INVALID_FILE, "The file is empty");
        }
        if (file.getSize() > WaybillImportFile.MAX_BYTES) {
            throw new BusinessException(ErrorCode.WAYBILL_IMPORT_INVALID_FILE,
                    "The file is over " + WaybillImportFile.MAX_BYTES + " bytes");
        }
        try (InputStream in = file.getInputStream()) {
            return in.readNBytes(WaybillImportFile.MAX_BYTES + 1);
        } catch (IOException ex) {
            throw new StorageException("Could not read the multipart upload", ex);
        }
    }

    private static WaybillImportResponse toResponse(WaybillImports.ImportView v) {
        return new WaybillImportResponse(v.id(), v.fileName(), v.uploadedBy(), v.uploadedAt(), v.rowsTotal(),
                v.rowsApplied(), v.rowsRejected(), v.lines().stream().map(WaybillImportController::toResponse).toList());
    }

    private static WaybillImportResponse.Line toResponse(WaybillImports.Line l) {
        return new WaybillImportResponse.Line(l.line(), l.loadCode(), l.waybillNo(), l.vehiclePlate(), l.driverName(),
                l.driverPhone(), l.loadId(), l.outcome(), l.problem(), l.holderCode());
    }
}
