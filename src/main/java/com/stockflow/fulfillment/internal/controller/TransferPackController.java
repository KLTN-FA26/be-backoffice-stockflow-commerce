package com.stockflow.fulfillment.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.fulfillment.internal.controller.dto.OpenPackageRequest;
import com.stockflow.fulfillment.internal.controller.dto.PackingCompletionRequest;
import com.stockflow.fulfillment.internal.controller.dto.SealPackageRequest;
import com.stockflow.fulfillment.internal.controller.dto.TransferCartonRequest;
import com.stockflow.fulfillment.internal.controller.dto.TransferKitRequest;
import com.stockflow.fulfillment.internal.controller.dto.TransferPackRequest;
import com.stockflow.fulfillment.internal.controller.dto.TransferScanRequest;
import com.stockflow.fulfillment.internal.service.TransferPacks;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
 * Packing a transfer order at its source warehouse (SCRUM-287 PR T; kltn-docs 10 §4 step 5, 08 step 5-9; LP-9, LP2-6,
 * LP2-8). The packer's permissions, as at an order's packing table: {@code fulfillment-packing:UPDATE} to pack,
 * {@code fulfillment-packages:READ} to look. Limited to the transfer's source warehouse. Line ids are the transfer's.
 */
@RestController
@RequestMapping("/api/v1/fulfillment/transfer-packs")
@Tag(name = "Fulfillment - transfer packing")
class TransferPackController {

    private final TransferPacks packs;

    TransferPackController(TransferPacks packs) {
        this.packs = packs;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Open the packing session of an APPROVED or PICKING full-truckload transfer (APPROVED starts "
            + "picking); a session open or packed already is answered as it is. 409 TRANSFER_NOT_FULL_TRUCKLOAD, "
            + "INVALID_TRANSFER_TRANSITION, PACKING_TAKEN")
    @RequiresPermission(resource = FulfillmentResources.PACKING, action = Action.UPDATE)
    @Auditable(action = AuditAction.CREATE, resourceType = "transfer-pack", resourceId = "#result?.data()?.packId()")
    public ApiResponse<TransferPacks.Session> open(@Valid @RequestBody TransferPackRequest request,
                                                   @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(packs.open(request.transferOrderId(), actor));
    }

    @GetMapping
    @Operation(summary = "The transfer's packing session - its live one, else its latest - without starting anything "
            + "(a reader reaches it from the transfer). 404 when it was never packed")
    @RequiresPermission(resource = FulfillmentResources.PACKAGES, action = Action.READ)
    public ApiResponse<TransferPacks.Session> current(@RequestParam UUID transferOrderId) {
        return ApiResponse.ok(packs.current(transferOrderId));
    }

    @PostMapping("/{packId}/packages/{packageId}/reopening")
    @Operation(summary = "A sealed package opened again before packing completes (as at an order's table)")
    @RequiresPermission(resource = FulfillmentResources.PACKING, action = Action.UPDATE)
    @Auditable(action = AuditAction.TRANSITION, resourceType = "fulfillment-package", resourceId = "#packageId")
    public ApiResponse<TransferPacks.Parcel> reopen(@PathVariable UUID packId, @PathVariable UUID packageId,
                                                    @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(packs.reopen(packId, packageId, actor));
    }

    @GetMapping("/{packId}/packages/{packageId}/label")
    @Operation(summary = "The internal label of a sealed transfer package (transfer, n of N, from / to warehouse, "
            + "weight, size, flags, contents, kit part); printing it makes an outdated label current again")
    @RequiresPermission(resource = FulfillmentResources.PACKING, action = Action.READ)
    public ResponseEntity<ApiResponse<TransferPacks.Label>> label(@PathVariable UUID packId,
            @PathVariable UUID packageId, @AuthenticatedUser CurrentUser actor) {
        // Never cached: a reprint must show the label as it is now (as the order label).
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(packs.label(packId,
                packageId, actor)));
    }

    @GetMapping("/{packId}")
    @Operation(summary = "A transfer's packing session: lines (requested, packed, remaining, kit suggested) and "
            + "packages")
    @RequiresPermission(resource = FulfillmentResources.PACKAGES, action = Action.READ)
    public ApiResponse<TransferPacks.Session> get(@PathVariable UUID packId) {
        return ApiResponse.ok(packs.get(packId));
    }

    @PostMapping("/{packId}/packages")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "A new empty package (KIT-FORCE: kitOverrideReason for an item that ships as several boxes)")
    @RequiresPermission(resource = FulfillmentResources.PACKING, action = Action.UPDATE)
    @Auditable(action = AuditAction.CREATE, resourceType = "fulfillment-package",
            resourceId = "#result?.data()?.packageId()")
    public ApiResponse<TransferPacks.Parcel> openPackage(@PathVariable UUID packId,
            @Valid @RequestBody(required = false) OpenPackageRequest request, @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(packs.openPackage(packId, request == null ? null : request.packageType(),
                request == null ? null : request.kitOverrideReason(), actor));
    }

    @PostMapping("/{packId}/package-kits")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Kit packing (LP2-6): the N packages of a kit for one unit of a transfer line whose item ships "
            + "as N > 1 boxes; scan the unit into part 1, seal every part. 422 KIT_PART_COUNT_MISMATCH")
    @RequiresPermission(resource = FulfillmentResources.PACKING, action = Action.UPDATE)
    @Auditable(action = AuditAction.CREATE, resourceType = "fulfillment-package",
            resourceId = "#result?.data()?.kitGroupId()")
    public ApiResponse<TransferPacks.Kit> openKit(@PathVariable UUID packId,
            @Valid @RequestBody TransferKitRequest request, @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(packs.openKit(packId, request.transferLineId(), request.partCount(),
                request.packageType(), actor));
    }

    @PostMapping("/{packId}/packages/{packageId}/scans")
    @Operation(summary = "One scan into an open package (SKU or barcode, lot when the line names one); the same scanId "
            + "again changes nothing. 409 PACK_ITEM_NOT_IN_ORDER, PACK_QUANTITY_EXCEEDS_ORDER, PACK_LOT_MISMATCH")
    @RequiresPermission(resource = FulfillmentResources.PACKING, action = Action.UPDATE)
    @Auditable(action = AuditAction.UPDATE, resourceType = "fulfillment-package", resourceId = "#packageId")
    public ApiResponse<TransferPacks.Session> scan(@PathVariable UUID packId, @PathVariable UUID packageId,
            @Valid @RequestBody TransferScanRequest request, @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(packs.scan(packId, packageId, new TransferPacks.Scan(request.scanId(),
                request.scannedCode(), request.lotNumber(), request.quantity(), request.transferLineId(),
                request.kitOverrideReason()), actor));
    }

    @PostMapping("/{packId}/packages/{packageId}/seal")
    @Operation(summary = "Weighed, measured, sealed with the real weight and size (08 BR-03) - a carton shipped as it "
            + "is too (LP2-8)")
    @RequiresPermission(resource = FulfillmentResources.PACKING, action = Action.UPDATE)
    @Auditable(action = AuditAction.TRANSITION, resourceType = "fulfillment-package", resourceId = "#packageId")
    public ApiResponse<TransferPacks.Parcel> seal(@PathVariable UUID packId, @PathVariable UUID packageId,
            @Valid @RequestBody SealPackageRequest request, @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(packs.seal(packId, packageId, new TransferPacks.Seal(request.weightKg(),
                request.lengthCm(), request.widthCm(), request.heightCm(), request.packageType(), request.rotatable(),
                request.fragile(), request.kitOverrideReason()), actor));
    }

    @DeleteMapping("/{packId}/packages/{packageId}")
    @Operation(summary = "Take a package apart before it left (a kit whole); its units count as unpacked again")
    @RequiresPermission(resource = FulfillmentResources.PACKING, action = Action.UPDATE)
    @Auditable(action = AuditAction.DELETE, resourceType = "fulfillment-package", resourceId = "#packageId")
    public ApiResponse<TransferPacks.Session> voidPackage(@PathVariable UUID packId, @PathVariable UUID packageId,
                                                          @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(packs.voidPackage(packId, packageId, actor));
    }

    @PostMapping("/{packId}/cartons")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "LP2-8: ship an unopened stock carton as it is - a package holding all its units, its packing "
            + "unit's size and weight suggested; seal it with the measured ones. 409 STOCK_CARTON_NOT_SHIPPABLE")
    @RequiresPermission(resource = FulfillmentResources.PACKING, action = Action.UPDATE)
    @Auditable(action = AuditAction.CREATE, resourceType = "fulfillment-package",
            resourceId = "#result?.data()?.parcel()?.packageId()")
    public ApiResponse<TransferPacks.CartonParcel> shipCarton(@PathVariable UUID packId,
            @Valid @RequestBody TransferCartonRequest request, @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(packs.shipCarton(packId, request.lpn(), request.packageType(), actor));
    }

    @PostMapping("/{packId}/completion")
    @Operation(summary = "Packing complete: every package sealed (kits whole); the transfer is packed - ready for a "
            + "load plan (sourceType TRANSFER). Less than requested only with partial and a note. 409 "
            + "PACKING_INCOMPLETE, PACKAGE_NOT_SEALED, KIT_INCOMPLETE")
    @RequiresPermission(resource = FulfillmentResources.PACKING, action = Action.UPDATE)
    @Auditable(action = AuditAction.TRANSITION, resourceType = "transfer-pack", resourceId = "#packId")
    public ApiResponse<TransferPacks.Session> complete(@PathVariable UUID packId,
            @Valid @RequestBody(required = false) PackingCompletionRequest request,
            @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(packs.complete(packId, request != null && request.partial(),
                request == null ? null : request.note(), actor));
    }
}
