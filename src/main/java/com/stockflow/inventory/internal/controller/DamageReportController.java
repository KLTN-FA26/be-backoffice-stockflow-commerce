package com.stockflow.inventory.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.PermissionResource;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.common.storage.FileUpload;
import com.stockflow.common.storage.StorageException;
import com.stockflow.inventory.internal.controller.dto.DamagePhotoLinkResponse;
import com.stockflow.inventory.internal.controller.dto.DamageReportResponse;
import com.stockflow.inventory.internal.controller.dto.DamageReportRowResponse;
import com.stockflow.inventory.internal.controller.dto.DisposeDamageRequest;
import com.stockflow.inventory.internal.controller.dto.ShipDamageToSupplierRequest;
import com.stockflow.inventory.internal.controller.dto.SupplierRefusalRequest;
import com.stockflow.inventory.internal.controller.dto.ReportDamageRequest;
import com.stockflow.inventory.internal.service.StockDamages;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Damaged stock (SCRUM-475; docs 03 §5, BR-STK-003): reported on a shelf with photos by anyone of the warehouse, and
 * decided - written off, sent back to the supplier, or restored - by a warehouse manager who did not report it.
 *
 * <p>Two permissions on one resource, on purpose: reporting is floor work ({@code CREATE}: warehouse and QC staff),
 * deciding is a manager's ({@code APPROVE}), and the reporter can never decide their own report (four eyes, in the
 * aggregate and the table). Damage found while picking is reported on the pick line, through fulfillment.</p>
 *
 * <p>The report is {@code multipart/form-data}: a JSON part {@code report} and one to five image parts
 * {@code photos}. Retries carry the same {@code requestId} in the report part: the platform's
 * {@code Idempotency-Key} filter does not buffer uploads.</p>
 */
@RestController
@RequestMapping("/api/v1/inventory/damage-reports")
@Tag(name = "Damaged stock", description = "Damage reports with photos, and their write-off, return or restore")
@PermissionResource(
        code = InventoryResources.DAMAGE_REPORTS,
        group = "Inventory",
        label = "Damaged stock",
        route = "/inventory/damage-reports",
        apiPath = "/api/v1/inventory/damage-reports",
        actions = {Action.VIEW_PAGE, Action.READ, Action.CREATE, Action.APPROVE})
class DamageReportController {

    private static final Logger log = LoggerFactory.getLogger(DamageReportController.class);

    private final StockDamages damages;
    private final DamageReportWebMapper mapper;

    DamageReportController(StockDamages damages, DamageReportWebMapper mapper) {
        this.damages = damages;
        this.mapper = mapper;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Report units of one stock row damaged, with 1-5 photos: they move to quarantine as DAMAGED "
            + "and leave available-to-promise at once; free units only (held ones are reported at picking)")
    @RequiresPermission(resource = InventoryResources.DAMAGE_REPORTS, action = Action.CREATE,
            scope = DataScope.WAREHOUSE)
    public ApiResponse<DamageReportResponse> report(@Valid @RequestPart("report") ReportDamageRequest report,
                                                    @RequestPart(name = "photos", required = false)
                                                    List<MultipartFile> photos,
                                                    @AuthenticatedUser CurrentUser user) {
        List<InputStream> opened = new ArrayList<>();
        try {
            List<FileUpload> uploads = new ArrayList<>();
            for (MultipartFile photo : photos == null ? List.<MultipartFile>of() : photos) {
                InputStream content = photo.getInputStream();
                opened.add(content);
                uploads.add(new FileUpload(photo.getOriginalFilename(), photo.getContentType(), photo.getSize(),
                        content));
            }
            return ApiResponse.ok(mapper.toResponse(damages.report(new StockDamages.ReportDamageCommand(
                    report.requestId(), report.stockItemId(), report.quantity(), report.reason(), report.note(),
                    report.damageLocationCode(), user.userId()), uploads)));
        } catch (IOException failure) {
            throw new StorageException("Could not read the multipart upload", failure);
        } finally {
            closeQuietly(opened);
        }
    }

    @GetMapping
    @Operation(summary = "Damage reports of my warehouses, newest first; status=OPEN is the manager's queue")
    @RequiresPermission(resource = InventoryResources.DAMAGE_REPORTS, action = Action.READ,
            scope = DataScope.WAREHOUSE)
    public ApiResponse<PageResponse<DamageReportRowResponse>> list(
            @RequestParam(required = false) UUID warehouseId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String sku,
            @RequestParam(required = false) String origin,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        return ApiResponse.ok(damages.list(warehouseId, status, sku, origin, page,
                size == null ? Pages.DEFAULT_PAGE_SIZE : size, sort).map(mapper::toResponse));
    }

    @GetMapping("/damage-areas")
    @Operation(summary = "The usable quarantine areas of a warehouse, where damaged goods can be taken")
    @RequiresPermission(resource = InventoryResources.DAMAGE_REPORTS, action = Action.READ,
            scope = DataScope.WAREHOUSE)
    public ApiResponse<List<String>> damageAreas(@RequestParam UUID warehouseId) {
        return ApiResponse.ok(damages.damageAreas(warehouseId));
    }

    @GetMapping("/{reportId}")
    @Operation(summary = "One damage report with its photos and decisions")
    @RequiresPermission(resource = InventoryResources.DAMAGE_REPORTS, action = Action.READ,
            scope = DataScope.WAREHOUSE)
    public ApiResponse<DamageReportResponse> find(@PathVariable UUID reportId) {
        return ApiResponse.ok(mapper.toResponse(damages.find(reportId)));
    }

    @GetMapping("/{reportId}/photos/{photoId}/download-url")
    @Operation(summary = "A short-lived link to one photo of a damage report")
    @RequiresPermission(resource = InventoryResources.DAMAGE_REPORTS, action = Action.READ,
            scope = DataScope.WAREHOUSE)
    public ResponseEntity<ApiResponse<DamagePhotoLinkResponse>> photo(@PathVariable UUID reportId,
                                                                      @PathVariable UUID photoId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(mapper.toResponse(damages.photoLink(reportId, photoId))));
    }

    @PostMapping("/{reportId}/dispositions")
    @Operation(summary = "Decide some units of a damage report: WRITE_OFF (posted at once, destruction record "
            + "numbered), RETURN_TO_SUPPLIER (intent) or RESTORE (back onto a bin); never the reporter")
    @RequiresPermission(resource = InventoryResources.DAMAGE_REPORTS, action = Action.APPROVE,
            scope = DataScope.WAREHOUSE)
    public ApiResponse<DamageReportResponse> dispose(@PathVariable UUID reportId,
                                                     @Valid @RequestBody DisposeDamageRequest request,
                                                     @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(mapper.toResponse(damages.dispose(reportId, new StockDamages.DisposeDamageCommand(
                request.requestId(), request.disposition(), request.quantity(), request.note(),
                request.restoreLocationCode(), user.userId()))));
    }

    /**
     * Units of a RETURN_TO_SUPPLIER decision handed to the supplier, or the supplier's carrier, under a delivery note:
     * {@code quantity} of them, or all it has left. They leave quarantine and the books now; until this, they stay
     * DAMAGED where they are. A manager's act, as the decision was; the same {@code requestId} again answers with the
     * report as it is.
     */
    @PostMapping("/{reportId}/dispositions/{dispositionId}/supplier-shipment")
    @Operation(summary = "Hand the goods of a RETURN_TO_SUPPLIER decision to the supplier: off quarantine and the books")
    @RequiresPermission(resource = InventoryResources.DAMAGE_REPORTS, action = Action.APPROVE,
            scope = DataScope.WAREHOUSE)
    public ApiResponse<DamageReportResponse> shipToSupplier(@PathVariable UUID reportId,
                                                            @PathVariable UUID dispositionId,
                                                            @Valid @RequestBody ShipDamageToSupplierRequest request,
                                                            @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(mapper.toResponse(damages.shipToSupplier(reportId, dispositionId,
                new StockDamages.ShipToSupplierCommand(request.requestId(), request.quantity(), request.deliveryNote(),
                        request.note(), user.userId()))));
    }

    /**
     * The supplier refused units of a RETURN_TO_SUPPLIER decision, or took fewer: they stay in quarantine and wait for
     * a decision again (write off, restore, or another return). The note says what the supplier answered.
     */
    @PostMapping("/{reportId}/dispositions/{dispositionId}/supplier-refusal")
    @Operation(summary = "Record units the supplier refused to take back: they wait for a decision again")
    @RequiresPermission(resource = InventoryResources.DAMAGE_REPORTS, action = Action.APPROVE,
            scope = DataScope.WAREHOUSE)
    public ApiResponse<DamageReportResponse> refuseReturn(@PathVariable UUID reportId, @PathVariable UUID dispositionId,
                                                          @Valid @RequestBody SupplierRefusalRequest request,
                                                          @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(mapper.toResponse(damages.refuseReturn(reportId, dispositionId,
                new StockDamages.RefuseReturnCommand(request.requestId(), request.quantity(), request.note(),
                        user.userId()))));
    }

    private static void closeQuietly(List<InputStream> streams) {
        for (InputStream stream : streams) {
            try {
                stream.close();
            } catch (IOException ignored) {
                log.debug("A multipart stream did not close cleanly");
            }
        }
    }
}
