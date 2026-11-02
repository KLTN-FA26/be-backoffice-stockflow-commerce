package com.stockflow.inventory.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.domain.BusinessCalendar;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.PermissionResource;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.inventory.internal.controller.dto.StockPackageDetailResponse;
import com.stockflow.inventory.internal.controller.dto.StockPackageLabelRequest;
import com.stockflow.inventory.internal.controller.dto.StockPackageLocationLineResponse;
import com.stockflow.inventory.internal.controller.dto.StockPackageResponse;
import com.stockflow.inventory.internal.domain.StockPackageStatus;
import com.stockflow.inventory.internal.service.StockPackageLabel;
import com.stockflow.inventory.internal.service.StockPackageLabelPdfRenderer;
import com.stockflow.inventory.internal.service.StockPackageLabels;
import com.stockflow.inventory.internal.service.StockPackageLookup;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Cartons (SCRUM-473): looking one up by its LPN, what a location holds by carton, a list of cartons, and printing
 * LPN labels (text, QR, Code 128) for the cartons of a receipt line or a reprint by LPN. Looking up is
 * {@code inventory-stock-packages:READ}, which staff, planners, QC and managers hold; printing and reprinting is
 * {@code CREATE}, which only warehouse staff and managers hold.
 *
 * <p>Like location labels, the print answers a binary on success and the JSON envelope on error.</p>
 */
@RestController
@Tag(name = "Stock packages", description = "Carton lookup, and LPN labels for cartons as a PDF to print")
@RequestMapping("/api/v1/inventory/stock-packages")
@PermissionResource(
        code = InventoryResources.STOCK_PACKAGES,
        group = "Inventory",
        label = "Cartons",
        route = "/inventory/stock-packages",
        apiPath = "/api/v1/inventory/stock-packages",
        actions = {Action.VIEW_PAGE, Action.READ, Action.CREATE})
class StockPackageController {

    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");

    private final StockPackageLabels labels;
    private final StockPackageLabelPdfRenderer renderer;
    private final StockPackageLookup lookup;
    private final StockPackageWebMapper mapper;
    private final Clock clock;

    StockPackageController(StockPackageLabels labels, StockPackageLabelPdfRenderer renderer, StockPackageLookup lookup,
                           StockPackageWebMapper mapper, Clock clock) {
        this.labels = labels;
        this.renderer = renderer;
        this.lookup = lookup;
        this.mapper = mapper;
        this.clock = clock;
    }

    @GetMapping
    @Operation(summary = "Cartons, page by page",
            description = "location is one exact location code; status is repeatable and defaults to the live "
                    + "cartons (RECEIVING, IN_STOCK). Only cartons in the caller's warehouses are listed; naming a "
                    + "location in another warehouse is 403. Sort: lpn (default), locationCode, sku.")
    @RequiresPermission(resource = InventoryResources.STOCK_PACKAGES, action = Action.READ)
    public ApiResponse<PageResponse<StockPackageResponse>> cartons(
            @RequestParam(required = false) String location,
            @RequestParam(required = false) String sku,
            @RequestParam(name = "status", required = false) List<StockPackageStatus> statuses,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        return ApiResponse.ok(lookup.cartons(new StockPackageLookup.CartonQuery(location, sku, statuses, page,
                size == null ? Pages.DEFAULT_PAGE_SIZE : size, sort)).map(mapper::toResponse));
    }

    @GetMapping("/location-summary")
    @Operation(summary = "What one location holds, by carton",
            description = "One exact location code, a bin or an area. A line per SKU, lot and stock status: unopened "
                    + "cartons and their units, opened cartons and what is left in them, loose units, the total. "
                    + "404 LOCATION_NOT_FOUND; 403 for a location in another warehouse.")
    @RequiresPermission(resource = InventoryResources.STOCK_PACKAGES, action = Action.READ)
    public ApiResponse<List<StockPackageLocationLineResponse>> locationSummary(@RequestParam String location) {
        return ApiResponse.ok(lookup.atLocation(location).stream().map(mapper::toResponse).toList());
    }

    @GetMapping("/{lpn}")
    @Operation(summary = "One carton by its LPN",
            description = "The LPN in either case. What is left in it is the sum of its stock rows, listed in "
                    + "contents. 404 STOCK_PACKAGE_NOT_FOUND; 403 for a carton in another warehouse, whatever its "
                    + "status.")
    @RequiresPermission(resource = InventoryResources.STOCK_PACKAGES, action = Action.READ)
    public ApiResponse<StockPackageDetailResponse> carton(@PathVariable String lpn) {
        return ApiResponse.ok(mapper.toResponse(lookup.byLpn(lpn)));
    }

    /**
     * Two steps on purpose: the service's read-only transaction ends before the renderer starts, so no database
     * connection is held while up to 500 labels are drawn.
     */
    @PostMapping(value = "/labels", produces = MediaType.APPLICATION_PDF_VALUE)
    @Operation(summary = "Print carton labels as a PDF",
            description = "Each label carries the LPN as text, as a QR code and as a Code 128 barcode, with the SKU "
                    + "and received / nominal unit. Send Accept: application/pdf, application/json - "
                    + "errors are the usual JSON envelope: 404 STOCK_PACKAGE_NOT_FOUND, 409 "
                    + "INVALID_STOCK_PACKAGE_TRANSITION for a VOID, CONSUMED or LOST carton, 422 "
                    + "STOCK_PACKAGE_LABELS_EMPTY and STOCK_PACKAGE_LABELS_TOO_MANY (over 500 labels).")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "The labels",
            content = @Content(mediaType = MediaType.APPLICATION_PDF_VALUE,
                    schema = @Schema(type = "string", format = "binary")))
    @RequiresPermission(resource = InventoryResources.STOCK_PACKAGES, action = Action.CREATE)
    public ResponseEntity<byte[]> labels(@Valid @RequestBody StockPackageLabelRequest request) {
        List<StockPackageLabel> sheet = labels.sheetFor(request.receiptLineId(), request.lpns());
        byte[] pdf = renderer.render(sheet, request.format());
        String fileName = "carton-labels-%s.pdf".formatted(
                FILE_TIME.format(clock.instant().atZone(BusinessCalendar.ZONE)));
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(fileName).build()
                        .toString())
                .body(pdf);
    }
}
