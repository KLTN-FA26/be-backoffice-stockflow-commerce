package com.stockflow.fulfillment.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.common.security.Roles;
import com.stockflow.fulfillment.internal.controller.dto.OpenPackageRequest;
import com.stockflow.fulfillment.internal.controller.dto.PackScanRequest;
import com.stockflow.fulfillment.internal.controller.dto.PackageLabelResponse;
import com.stockflow.fulfillment.internal.controller.dto.PackageResponse;
import com.stockflow.fulfillment.internal.controller.dto.PackingQueueRowResponse;
import com.stockflow.fulfillment.internal.controller.dto.PackingSessionResponse;
import com.stockflow.fulfillment.internal.controller.dto.SealPackageRequest;
import com.stockflow.fulfillment.internal.domain.PackingSession;
import com.stockflow.fulfillment.internal.service.Packing;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The packing station (G4-5, SCRUM-278/281/282; kltn-docs 08): the queue, the packing session of a task, its packages
 * and their labels. Completing packing stays on {@code POST /tasks/{taskId}/packing-completion}.
 *
 * <p>Permission resource {@code fulfillment-packages} ({@link FulfillmentPackageResourceDeclaration}): READ to look,
 * CREATE to pack. The order coordinator and QC hold {@code CREATE} for other reasons (design-integrity
 * reverification); packing itself is limited to warehouse staff and managers by {@code @PreAuthorize}. Every call is
 * limited to the caller's warehouses.</p>
 */
@RestController
@RequestMapping("/api/v1/fulfillment")
@Validated
class PackingController {

    private static final String PACKERS = "hasAnyAuthority('" + Roles.WAREHOUSE_STAFF + "','"
            + Roles.WAREHOUSE_MANAGER + "')";

    private final Packing packing;
    private final PackingWebMapper mapper;

    PackingController(Packing packing, PackingWebMapper mapper) {
        this.packing = packing;
        this.mapper = mapper;
    }

    /** Picked orders waiting at the packing table, oldest pick first (SCRUM-281). */
    @GetMapping("/packing-queue")
    @RequiresPermission(resource = FulfillmentResources.PACKAGES, action = Action.READ)
    @PreAuthorize("hasAnyAuthority('" + Roles.WAREHOUSE_STAFF + "','" + Roles.WAREHOUSE_MANAGER + "','"
            + Roles.QC_STAFF + "')")
    public ApiResponse<PageResponse<PackingQueueRowResponse>> queue(@AuthenticatedUser CurrentUser actor,
            @RequestParam(required = false) UUID warehouseId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(packing.queue(warehouseId, status, page, size == null ? Pages.DEFAULT_PAGE_SIZE : size,
                actor).map(mapper::toResponse));
    }

    /** A packer takes the order at the table; again, answered as it is. 409 PACKING_NOT_READY before picking ends. */
    @PostMapping("/tasks/{taskId}/packing-start")
    @RequiresPermission(resource = FulfillmentResources.PACKAGES, action = Action.CREATE)
    @PreAuthorize(PACKERS)
    @Auditable(action = AuditAction.TRANSITION, resourceType = "fulfillment-task", resourceId = "#taskId")
    public ApiResponse<PackingSessionResponse> start(@PathVariable UUID taskId, @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(mapper.toResponse(packing.start(taskId, actor)));
    }

    @GetMapping("/tasks/{taskId}/packing-session")
    @RequiresPermission(resource = FulfillmentResources.PACKAGES, action = Action.READ)
    @PreAuthorize("hasAnyAuthority('" + Roles.WAREHOUSE_STAFF + "','" + Roles.WAREHOUSE_MANAGER + "','"
            + Roles.QC_STAFF + "')")
    public ApiResponse<PackingSessionResponse> session(@PathVariable UUID taskId,
                                                       @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(mapper.toResponse(packing.session(taskId, actor)));
    }

    /** A new empty package. Send an {@code Idempotency-Key}: each call opens another. */
    @PostMapping("/tasks/{taskId}/packages")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(resource = FulfillmentResources.PACKAGES, action = Action.CREATE)
    @PreAuthorize(PACKERS)
    @Auditable(action = AuditAction.CREATE, resourceType = "fulfillment-package",
            resourceId = "#result?.data()?.packageId()")
    public ApiResponse<PackageResponse> openPackage(@PathVariable UUID taskId,
            @Valid @RequestBody(required = false) OpenPackageRequest request, @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(mapper.toResponse(packing.openPackage(taskId,
                request == null ? null : request.packageType(), actor)));
    }

    /**
     * One scan into an open package (08 step 2): SKU or barcode, lot or serial when the goods have one. The same
     * {@code scanId} again answers 200 with nothing changed. Wrong item 409 PACK_ITEM_NOT_IN_ORDER, over-scan
     * PACK_QUANTITY_EXCEEDS_ORDER / PACK_QUANTITY_EXCEEDS_PICKED, lot PACK_LOT_MISMATCH, serial PACK_SERIAL_MISMATCH.
     */
    @PostMapping("/tasks/{taskId}/packages/{packageId}/scans")
    @RequiresPermission(resource = FulfillmentResources.PACKAGES, action = Action.CREATE)
    @PreAuthorize(PACKERS)
    @Auditable(action = AuditAction.UPDATE, resourceType = "fulfillment-package", resourceId = "#packageId")
    public ApiResponse<PackingSessionResponse> scan(@PathVariable UUID taskId, @PathVariable UUID packageId,
            @Valid @RequestBody PackScanRequest request, @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(mapper.toResponse(packing.scan(taskId, packageId, new Packing.Scan(request.scanId(),
                request.scannedCode(), request.lotNumber(), request.serialNumber(), request.quantity(),
                request.orderLineId()), actor)));
    }

    /**
     * Takes {@code quantity} units of an order line out of an open package again. Send a {@code scanId}: the same one
     * again answers 200 with nothing removed twice.
     */
    @DeleteMapping("/tasks/{taskId}/packages/{packageId}/lines/{orderLineId}")
    @RequiresPermission(resource = FulfillmentResources.PACKAGES, action = Action.CREATE)
    @PreAuthorize(PACKERS)
    @Auditable(action = AuditAction.UPDATE, resourceType = "fulfillment-package", resourceId = "#packageId")
    public ApiResponse<PackingSessionResponse> removeUnits(@PathVariable UUID taskId, @PathVariable UUID packageId,
            @PathVariable UUID orderLineId, @RequestParam @Positive @Max(PackingSession.MAX_UNITS_PER_SCAN) int quantity,
            @RequestParam(required = false) @Size(max = 64) String lotNumber,
            @RequestParam(required = false) @Size(max = 100) String serialNumber,
            @RequestParam(required = false) UUID scanId,
            @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(mapper.toResponse(packing.removeUnits(taskId, packageId, orderLineId, lotNumber,
                serialNumber, quantity, scanId, actor)));
    }

    /** Weighed, measured, sealed (08 step 5-7). A missing measure is 400 VALIDATION_FAILED; empty 409 PACKAGE_EMPTY. */
    @PostMapping("/tasks/{taskId}/packages/{packageId}/seal")
    @RequiresPermission(resource = FulfillmentResources.PACKAGES, action = Action.CREATE)
    @PreAuthorize(PACKERS)
    @Auditable(action = AuditAction.TRANSITION, resourceType = "fulfillment-package", resourceId = "#packageId")
    public ApiResponse<PackageResponse> seal(@PathVariable UUID taskId, @PathVariable UUID packageId,
            @Valid @RequestBody SealPackageRequest request, @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(mapper.toResponse(packing.seal(taskId, packageId, new Packing.Seal(request.weightKg(),
                request.lengthCm(), request.widthCm(), request.heightCm(), request.packageType(), request.rotatable(),
                request.fragile()), actor)));
    }

    /** A sealed package opened again before it shipped (409 PACKAGE_SHIPPED after). */
    @PostMapping("/tasks/{taskId}/packages/{packageId}/reopening")
    @RequiresPermission(resource = FulfillmentResources.PACKAGES, action = Action.CREATE)
    @PreAuthorize(PACKERS)
    @Auditable(action = AuditAction.TRANSITION, resourceType = "fulfillment-package", resourceId = "#packageId")
    public ApiResponse<PackageResponse> reopen(@PathVariable UUID taskId, @PathVariable UUID packageId,
                                               @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(mapper.toResponse(packing.reopen(taskId, packageId, actor)));
    }

    /** Takes a package apart before it shipped: VOIDED, its goods count as unpacked again, its label void. */
    @DeleteMapping("/tasks/{taskId}/packages/{packageId}")
    @RequiresPermission(resource = FulfillmentResources.PACKAGES, action = Action.CREATE)
    @PreAuthorize(PACKERS)
    @Auditable(action = AuditAction.DELETE, resourceType = "fulfillment-package", resourceId = "#packageId")
    public ApiResponse<PackingSessionResponse> voidPackage(@PathVariable UUID taskId, @PathVariable UUID packageId,
                                                           @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(mapper.toResponse(packing.voidPackage(taskId, packageId, actor)));
    }

    /** What the internal label prints (08 step 6); the front end renders it. A package not sealed is 409. */
    @GetMapping("/tasks/{taskId}/packages/{packageId}/label")
    @RequiresPermission(resource = FulfillmentResources.PACKAGES, action = Action.READ)
    @PreAuthorize(PACKERS)
    public ResponseEntity<ApiResponse<PackageLabelResponse>> label(@PathVariable UUID taskId,
            @PathVariable UUID packageId, @AuthenticatedUser CurrentUser actor) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(mapper.toResponse(packing.label(taskId, packageId, actor))));
    }
}
