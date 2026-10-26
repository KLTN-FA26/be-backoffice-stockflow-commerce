package com.stockflow.inventory.internal.controller;

import com.stockflow.common.domain.BusinessCalendar;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.PermissionResource;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.inventory.internal.controller.dto.StockPackageLabelRequest;
import com.stockflow.inventory.internal.service.StockPackageLabel;
import com.stockflow.inventory.internal.service.StockPackageLabelPdfRenderer;
import com.stockflow.inventory.internal.service.StockPackageLabels;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Printing carton labels (SCRUM-473): a PDF of LPN labels (text, QR, Code 128) for the cartons of a receipt line,
 * or a reprint by LPN. Printing and reprinting is {@code inventory-stock-packages:CREATE}, which warehouse staff
 * and managers hold; planners and QC only read.
 *
 * <p>Like location labels, it answers a binary on success and the JSON envelope on error.</p>
 */
@RestController
@Tag(name = "Stock packages: carton labels", description = "LPN labels for cartons, as a PDF to print")
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
    private final Clock clock;

    StockPackageController(StockPackageLabels labels, StockPackageLabelPdfRenderer renderer, Clock clock) {
        this.labels = labels;
        this.renderer = renderer;
        this.clock = clock;
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
