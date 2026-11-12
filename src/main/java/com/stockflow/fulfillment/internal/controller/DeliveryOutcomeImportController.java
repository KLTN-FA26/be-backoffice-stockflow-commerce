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
import com.stockflow.fulfillment.internal.domain.DeliveryOutcomeFile;
import com.stockflow.fulfillment.internal.service.DeliveryOutcomeImports;
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
 * Delivery outcomes applied from the system's file template (WF-05 fix plan item 5; kltn-docs 09 §4.3).
 * {@code fulfillment-delivery-outcome-imports:READ} for the template and past imports; {@code :CREATE} to preview and
 * apply a file - the warehouse manager's. Every line is limited to the caller's warehouses: a vehicle of another
 * warehouse is a refused line, not a refused file.
 */
@RestController
@RequestMapping("/api/v1/fulfillment/delivery-outcome-imports")
@Tag(name = "Fulfillment - carrier events")
class DeliveryOutcomeImportController {

    private final DeliveryOutcomeImports imports;

    DeliveryOutcomeImportController(DeliveryOutcomeImports imports) {
        this.imports = imports;
    }

    @GetMapping("/template")
    @Operation(summary = "The template: CSV UTF-8 with a byte-order mark; waybill number, outcome (DELIVERED / "
            + "DELIVERY_FAILED), failure reason, time, received by, note, shipment code, carrier code")
    @RequiresPermission(resource = FulfillmentResources.DELIVERY_OUTCOME_IMPORTS, action = Action.READ)
    public ResponseEntity<byte[]> template() {
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("delivery-outcome-import-template.csv").build().toString())
                .cacheControl(CacheControl.noStore())
                .body(imports.template());
    }

    @PostMapping(path = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Step 1: check each line of a file against the vehicle its waybill number names; nothing is "
            + "written. 400 DELIVERY_OUTCOME_IMPORT_INVALID_FILE")
    @RequiresPermission(resource = FulfillmentResources.DELIVERY_OUTCOME_IMPORTS, action = Action.CREATE)
    public ApiResponse<DeliveryOutcomeImports.Preview> preview(@RequestPart("file") MultipartFile file,
                                                               @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(imports.preview(read(file), actor));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Step 2: apply a file. Each accepted line becomes a carrier event (source FILE) decided by the "
            + "webhooks' rules - a repeat is DUPLICATE, an older report STALE - so the same file applied again changes "
            + "nothing; refused lines are kept with their reason. 400 DELIVERY_OUTCOME_IMPORT_INVALID_FILE")
    @RequiresPermission(resource = FulfillmentResources.DELIVERY_OUTCOME_IMPORTS, action = Action.CREATE)
    public ApiResponse<DeliveryOutcomeImports.ImportView> importFile(@RequestPart("file") MultipartFile file,
                                                                     @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(imports.importFile(read(file), file.getOriginalFilename(), actor));
    }

    @GetMapping
    @Operation(summary = "Imports the caller made (everyone's, for a caller not limited to warehouses); newest first")
    @RequiresPermission(resource = FulfillmentResources.DELIVERY_OUTCOME_IMPORTS, action = Action.READ)
    public ApiResponse<PageResponse<DeliveryOutcomeImports.ImportRow>> list(
            @RequestParam(defaultValue = "0") int page, @RequestParam(required = false) Integer size,
            @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(imports.list(page, size == null ? Pages.DEFAULT_PAGE_SIZE : size, actor));
    }

    @GetMapping("/{importId}")
    @Operation(summary = "An import, its lines, and what came of each accepted line's event. 404 "
            + "DELIVERY_OUTCOME_IMPORT_NOT_FOUND")
    @RequiresPermission(resource = FulfillmentResources.DELIVERY_OUTCOME_IMPORTS, action = Action.READ)
    public ApiResponse<DeliveryOutcomeImports.ImportView> get(@PathVariable UUID importId,
                                                              @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(imports.get(importId, actor));
    }

    /** The file's bytes, refused past the template's limit before it is read whole. */
    private static byte[] read(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.DELIVERY_OUTCOME_IMPORT_INVALID_FILE, "The file is empty");
        }
        if (file.getSize() > DeliveryOutcomeFile.MAX_BYTES) {
            throw new BusinessException(ErrorCode.DELIVERY_OUTCOME_IMPORT_INVALID_FILE,
                    "The file is over " + DeliveryOutcomeFile.MAX_BYTES + " bytes");
        }
        try (InputStream in = file.getInputStream()) {
            return in.readNBytes(DeliveryOutcomeFile.MAX_BYTES + 1);
        } catch (IOException ex) {
            throw new StorageException("Could not read the multipart upload", ex);
        }
    }
}
