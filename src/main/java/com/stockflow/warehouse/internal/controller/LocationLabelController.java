package com.stockflow.warehouse.internal.controller;

import com.stockflow.common.domain.BusinessCalendar;
import com.stockflow.common.i18n.Messages;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.warehouse.internal.controller.dto.LocationLabelRequest;
import com.stockflow.warehouse.internal.service.LabelWords;
import com.stockflow.warehouse.internal.service.LocationLabelPdfRenderer;
import com.stockflow.warehouse.internal.service.LocationLabelService;
import com.stockflow.warehouse.internal.service.LocationLabelSheet;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Printing location labels (SCRUM-97): a PDF of Code 128 + QR labels, each carrying a location code.
 * Guarded by {@link WarehouseResources#LOCATIONS} READ: printing is reading the map.
 *
 * <p>The only endpoint of the application that answers a binary rather than the JSON envelope. Its
 * errors are still the envelope - {@code GlobalExceptionHandler} presets the JSON content type, so
 * an error under {@code Accept: application/pdf} keeps its status instead of turning into a 406.</p>
 */
@RestController
@Tag(name = "Warehouse map: location labels", description = "Barcode labels for bins and storage areas, "
        + "as a PDF to print")
@RequestMapping("/api/v1")
class LocationLabelController {

    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");

    private final LocationLabelService labels;
    private final LocationLabelPdfRenderer renderer;
    private final Messages messages;
    private final Clock clock;

    LocationLabelController(LocationLabelService labels, LocationLabelPdfRenderer renderer, Messages messages,
                            Clock clock) {
        this.labels = labels;
        this.renderer = renderer;
        this.messages = messages;
        this.clock = clock;
    }

    /**
     * Two steps on purpose: the service's read-only transaction ends before the renderer starts, so no
     * database connection is held while a PDF of up to 2,000 labels is drawn.
     */
    @PostMapping(value = "/warehouses/{warehouseId}/location-labels", produces = MediaType.APPLICATION_PDF_VALUE)
    @Operation(summary = "Print location labels as a PDF",
            description = "Each label carries the location code as text, as a QR code and as a Code 128 barcode, "
                    + "in walking order: bins by shelf, level and bin, then storage areas. Send "
                    + "Accept: application/pdf, application/json - errors are the usual JSON envelope: "
                    + "404 WAREHOUSE_NOT_FOUND / SHELF_NOT_FOUND / SHELF_LEVEL_NOT_FOUND / ZONE_NOT_FOUND / "
                    + "LOCATION_NOT_FOUND, 409 LOCATION_INACTIVE for a named code off the layout, "
                    + "422 LOCATION_LABELS_EMPTY and LOCATION_LABELS_TOO_MANY (over 2000 labels).")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "The labels",
            content = @Content(mediaType = MediaType.APPLICATION_PDF_VALUE,
                    schema = @Schema(type = "string", format = "binary")))
    @RequiresPermission(resource = WarehouseResources.LOCATIONS, action = Action.READ)
    public ResponseEntity<byte[]> labels(@PathVariable UUID warehouseId,
                                         @Valid @RequestBody LocationLabelRequest request) {
        LocationLabelSheet sheet = labels.sheetFor(warehouseId, request.toSelection());
        byte[] pdf = renderer.render(sheet, request.format(), new LabelWords(
                messages.get("warehouse.label.shelf", "Shelf"),
                messages.get("warehouse.label.level", "Level"),
                messages.get("warehouse.label.bin", "Bin")));
        String fileName = "location-labels-%s-%s.pdf".formatted(sheet.warehousePrefix(),
                FILE_TIME.format(clock.instant().atZone(BusinessCalendar.ZONE)));
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(fileName).build()
                        .toString())
                .body(pdf);
    }
}
