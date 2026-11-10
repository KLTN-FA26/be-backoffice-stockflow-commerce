package com.stockflow.fulfillment.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.label.LabelFonts;
import com.stockflow.common.label.ShippingLabelSheet;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.fulfillment.internal.controller.dto.CartonCheckQueueRowResponse;
import com.stockflow.fulfillment.internal.controller.dto.CartonCheckSessionResponse;
import com.stockflow.fulfillment.internal.controller.dto.CartonRemovalRequest;
import com.stockflow.fulfillment.internal.controller.dto.CartonScanRequest;
import com.stockflow.fulfillment.internal.controller.dto.CartonSelectionRequest;
import com.stockflow.fulfillment.internal.service.CartonChecks;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The carton check and labelling station (team plan 4C-6; kltn-docs 08), at the dispatch area: the queue, the check
 * session of a task, scanning each carton's LPN, its shipping label (label, print, reprint), and removing a damaged
 * carton. Completing stays on {@code POST /tasks/{taskId}/packing-completion}; the repack station's endpoints of
 * {@code PackingController} stay until team plan 4C-7 removes them.
 *
 * <p>{@code fulfillment-packages:READ} to look at the queue and a session; {@code fulfillment-packing} to work the
 * station: UPDATE to start, scan, label, reprint and remove, READ to print the labels, and APPROVE to work another
 * worker's session (the warehouse manager, checked in the service - 08 BR-06). Every call is limited to the caller's
 * warehouses. No new permission: these are the packing station's, which the check station replaces.</p>
 */
@RestController
@RequestMapping("/api/v1/fulfillment")
@Validated
class CartonCheckController {

    private final CartonChecks checks;
    private final CartonCheckWebMapper mapper;
    private final LabelFonts fonts;

    CartonCheckController(CartonChecks checks, CartonCheckWebMapper mapper, LabelFonts fonts) {
        this.checks = checks;
        this.mapper = mapper;
        this.fonts = fonts;
    }

    /** Picked orders waiting at the check station, oldest pick first (08 §4.1 step 1). */
    @GetMapping("/packing-queue")
    @RequiresPermission(resource = FulfillmentResources.PACKAGES, action = Action.READ)
    public ApiResponse<PageResponse<CartonCheckQueueRowResponse>> queue(@AuthenticatedUser CurrentUser actor,
            @RequestParam(required = false) UUID warehouseId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(checks.queue(warehouseId, status, page, size == null ? Pages.DEFAULT_PAGE_SIZE : size,
                actor).map(mapper::toResponse));
    }

    /**
     * A worker starts the order's check (08 §4.1 step 2); again, answered as it is. 409 PACKING_NOT_READY before every
     * carton is at the dispatch area (a task still STAGED included), PACKING_TAKEN for another worker.
     */
    @PostMapping("/tasks/{taskId}/packing-start")
    @RequiresPermission(resource = FulfillmentResources.PACKING, action = Action.UPDATE)
    @Auditable(action = AuditAction.TRANSITION, resourceType = "fulfillment-task", resourceId = "#taskId")
    public ApiResponse<CartonCheckSessionResponse> start(@PathVariable UUID taskId,
                                                         @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(mapper.toResponse(checks.start(taskId, actor)));
    }

    @GetMapping("/tasks/{taskId}/packing-session")
    @RequiresPermission(resource = FulfillmentResources.PACKAGES, action = Action.READ)
    public ApiResponse<CartonCheckSessionResponse> session(@PathVariable UUID taskId,
                                                           @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(mapper.toResponse(checks.session(taskId, actor)));
    }

    /**
     * One LPN scanned (08 §4.2 step 3): the carton is CHECKED; again, 200 with nothing changed. 409 CARTON_NOT_IN_TASK
     * for a carton not picked for this task.
     */
    @PostMapping("/tasks/{taskId}/carton-checks")
    @RequiresPermission(resource = FulfillmentResources.PACKING, action = Action.UPDATE)
    @Auditable(action = AuditAction.UPDATE, resourceType = "fulfillment-task", resourceId = "#taskId")
    public ApiResponse<CartonCheckSessionResponse> check(@PathVariable UUID taskId,
            @Valid @RequestBody CartonScanRequest request, @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(mapper.toResponse(checks.check(taskId, request.lpn(), actor)));
    }

    /**
     * Labels the cartons named, or every CHECKED one (08 §4.3 steps 5-6): each takes its carton spec's size and weight
     * and is LABELED; the first label locks the order (08 BR-04). 422 CARTON_SPEC_UNMEASURED naming the SKUs whose carton
     * spec has no size or weight (BR-07); 409 CARTON_NOT_CHECKED. A retry is 200 with nothing changed: of the cartons
     * named, or - with no body - once no carton waits CHECKED and one is labelled. Then print them:
     * {@code POST .../shipping-labels}.
     */
    @PostMapping("/tasks/{taskId}/carton-labels")
    @RequiresPermission(resource = FulfillmentResources.PACKING, action = Action.UPDATE)
    @Auditable(action = AuditAction.TRANSITION, resourceType = "fulfillment-task", resourceId = "#taskId")
    public ApiResponse<CartonCheckSessionResponse> label(@PathVariable UUID taskId,
            @Valid @RequestBody(required = false) CartonSelectionRequest request,
            @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(mapper.toResponse(checks.label(taskId, request == null ? null : request.lpns(), actor)));
    }

    /**
     * A torn or lost label printed again (08 BR-08): its version goes up and the old label is void. Only the worker who
     * started the session or a warehouse manager (BR-06), also once it is completed. Send {@code fromVersion}, the version on the label being
     * replaced: a retry once the carton is past it answers 200 with nothing changed (an {@code Idempotency-Key} does the
     * same). Without it every call is a new version.
     */
    @PostMapping("/tasks/{taskId}/cartons/{lpn}/label-reprint")
    @RequiresPermission(resource = FulfillmentResources.PACKING, action = Action.UPDATE)
    @Auditable(action = AuditAction.UPDATE, resourceType = "fulfillment-task", resourceId = "#taskId")
    public ApiResponse<CartonCheckSessionResponse> reprint(@PathVariable UUID taskId,
            @PathVariable @Size(max = 30) String lpn, @RequestParam(required = false) @Min(1) Integer fromVersion,
            @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(mapper.toResponse(checks.reprint(taskId, lpn, fromVersion, actor)));
    }

    /**
     * A carton damaged outside removed from the order (08 §4.2 step 4): REMOVED, in the put-back queue to go into a
     * quarantine area. Again, 200 with nothing changed; 409 PACKAGE_SHIPPED once it is in a shipment.
     */
    @PostMapping("/tasks/{taskId}/cartons/{lpn}/removal")
    @RequiresPermission(resource = FulfillmentResources.PACKING, action = Action.UPDATE)
    @Auditable(action = AuditAction.TRANSITION, resourceType = "fulfillment-task", resourceId = "#taskId")
    public ApiResponse<CartonCheckSessionResponse> remove(@PathVariable UUID taskId,
            @PathVariable @Size(max = 30) String lpn,
            @Valid @RequestBody(required = false) CartonRemovalRequest request,
            @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(mapper.toResponse(checks.remove(taskId, lpn, request == null ? null : request.note(),
                actor)));
    }

    /**
     * The shipping labels as a PDF, one 100 x 150 mm page per carton (08 §4.3 step 6): the cartons named, or every
     * labelled carton not shipped yet. Prints the current version and changes nothing. Send {@code Accept:
     * application/pdf, application/json}: errors come as the JSON envelope - 409 CARTON_NOT_CHECKED for a carton not
     * labelled.
     */
    @PostMapping(value = "/tasks/{taskId}/shipping-labels", produces = MediaType.APPLICATION_PDF_VALUE)
    @RequiresPermission(resource = FulfillmentResources.PACKING, action = Action.READ)
    public ResponseEntity<byte[]> shippingLabels(@PathVariable UUID taskId,
            @Valid @RequestBody(required = false) CartonSelectionRequest request,
            @AuthenticatedUser CurrentUser actor) {
        List<ShippingLabelSheet.ShippingLabel> labels = checks.labels(taskId,
                request == null ? null : request.lpns(), actor);
        byte[] pdf = ShippingLabelSheet.render(fonts, labels);
        String fileName = "shipping-labels-%s.pdf".formatted(labels.getFirst().orderNumber());
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(fileName).build()
                        .toString())
                .body(pdf);
    }
}
