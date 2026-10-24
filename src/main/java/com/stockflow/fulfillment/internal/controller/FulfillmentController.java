package com.stockflow.fulfillment.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.PermissionResource;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.fulfillment.api.FulfillmentService;
import com.stockflow.fulfillment.api.FulfillmentTaskQuery;
import com.stockflow.fulfillment.api.PackingCompletion;
import com.stockflow.fulfillment.internal.controller.dto.AssigneeResponse;
import com.stockflow.fulfillment.internal.controller.dto.ConfirmPickRequest;
import com.stockflow.fulfillment.internal.controller.dto.FulfillmentArtifactAccessResponse;
import com.stockflow.fulfillment.internal.controller.dto.FulfillmentTaskResponse;
import com.stockflow.common.storage.FileUpload;
import com.stockflow.common.storage.StorageException;
import com.stockflow.fulfillment.internal.controller.dto.PackingCompletionRequest;
import com.stockflow.fulfillment.internal.controller.dto.PickDamageRequest;
import com.stockflow.fulfillment.internal.controller.dto.PickDamageResponse;
import com.stockflow.fulfillment.internal.controller.dto.PickLineResponse;
import com.stockflow.fulfillment.internal.controller.dto.PutBackRequest;
import com.stockflow.fulfillment.internal.controller.dto.PutBackRowResponse;
import com.stockflow.fulfillment.internal.service.PickLists;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Task-scoped warehouse flow with a non-bypassable design-integrity gate. */
@RestController
@RequestMapping("/api/v1/fulfillment")
@PermissionResource(code = FulfillmentResources.PICK_LISTS, group = "Fulfillment", label = "Pick lists",
        route = "/fulfillment/picks", apiPath = "/api/v1/fulfillment/tasks",
        actions = {Action.VIEW_PAGE, Action.READ, Action.UPDATE})
class FulfillmentController {

    private final FulfillmentService fulfillment;
    private final PickLists pickLists;
    private final FulfillmentWebMapper mapper;

    FulfillmentController(FulfillmentService fulfillment, PickLists pickLists, FulfillmentWebMapper mapper) {
        this.fulfillment = fulfillment;
        this.pickLists = pickLists;
        this.mapper = mapper;
    }

    @PostMapping("/tasks")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(resource = FulfillmentResources.TASK_ASSIGNMENT, action = Action.CREATE)
    @Auditable(action = AuditAction.CREATE, resourceType = "fulfillment-task", resourceId = "#result?.data()?.taskId()")
    public ApiResponse<FulfillmentTaskResponse> create(@Valid @RequestBody CreateTaskRequest request,
                                               @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(mapper.toResponse(fulfillment.createTask(request.orderId(), request.assignedUserId(), actor)));
    }

    @GetMapping("/tasks")
    @RequiresPermission(resource = FulfillmentResources.PICK_LISTS, action = Action.READ)
    public ApiResponse<PageResponse<FulfillmentTaskResponse>> list(@AuthenticatedUser CurrentUser actor,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID warehouseId,
            @RequestParam(required = false) UUID assignedUserId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(fulfillment
                .listTasks(actor, new FulfillmentTaskQuery(status, warehouseId, assignedUserId, page,
                        size == null ? Pages.DEFAULT_PAGE_SIZE : size))
                .map(mapper::toResponse));
    }

    /**
     * Who a task of one warehouse can go to: the active warehouse staff and managers assigned to it.
     * The warehouse is required, and a manager is limited to their own. The answer is deliberately not
     * paged: it is bounded by one warehouse's staff, and the screen needs all of them to pick from.
     */
    @GetMapping("/assignees")
    @RequiresPermission(resource = FulfillmentResources.TASK_ASSIGNMENT, action = Action.UPDATE)
    public ApiResponse<List<AssigneeResponse>> assignees(@RequestParam UUID warehouseId,
                                                         @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(fulfillment.assignees(warehouseId, actor).stream().map(mapper::toResponse).toList());
    }

    @PutMapping("/tasks/{taskId}/assignment")
    @RequiresPermission(resource = FulfillmentResources.TASK_ASSIGNMENT, action = Action.UPDATE)
    @Auditable(action = AuditAction.UPDATE, resourceType = "fulfillment-task", resourceId = "#taskId")
    public ApiResponse<FulfillmentTaskResponse> assign(@PathVariable UUID taskId,
            @Valid @RequestBody AssignmentRequest request, @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(mapper.toResponse(fulfillment.assign(taskId, request.assignedUserId(), actor)));
    }

    @GetMapping("/tasks/{taskId}")
    @RequiresPermission(resource = FulfillmentResources.PICK_LISTS, action = Action.READ)
    public ApiResponse<FulfillmentTaskResponse> find(@PathVariable UUID taskId,
                                             @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(mapper.toResponse(fulfillment.findTask(taskId, actor)));
    }

    /**
     * Starts picking, and plans the task's lines if it has none (a task opened before pick lines existed).
     * Starting a task already PICKING answers with it unchanged.
     */
    @PostMapping("/tasks/{taskId}/picking-start")
    @RequiresPermission(resource = FulfillmentResources.PICKING, action = Action.UPDATE)
    @Auditable(action = AuditAction.TRANSITION, resourceType = "fulfillment-task", resourceId = "#taskId")
    public ApiResponse<FulfillmentTaskResponse> startPicking(@PathVariable UUID taskId,
                                                     @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(mapper.toResponse(fulfillment.startPicking(taskId, actor)));
    }

    /** Closes picking once every line is PICKED: {@code 409 PICK_LINES_INCOMPLETE} otherwise (docs 07 step 9). */
    @PostMapping("/tasks/{taskId}/picking-completion")
    @RequiresPermission(resource = FulfillmentResources.PICKING, action = Action.UPDATE)
    @Auditable(action = AuditAction.TRANSITION, resourceType = "fulfillment-task", resourceId = "#taskId")
    public ApiResponse<FulfillmentTaskResponse> completePicking(@PathVariable UUID taskId,
                                                        @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(mapper.toResponse(fulfillment.completePicking(taskId, actor)));
    }

    /**
     * The pick list: one line per hold of the order's stock lines, in walking order (print lines have
     * none, docs 07 BR-06). The assignee, and managers, QC and coordinators of the task's warehouse.
     */
    @GetMapping("/tasks/{taskId}/pick-lines")
    @RequiresPermission(resource = FulfillmentResources.PICK_LISTS, action = Action.READ)
    public ApiResponse<PageResponse<PickLineResponse>> pickLines(@PathVariable UUID taskId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(required = false) Integer size,
            @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(pickLists.lines(taskId, page, size == null ? Pages.DEFAULT_PAGE_SIZE : size, actor)
                .map(mapper::toResponse));
    }

    /**
     * Confirms one line by scan (SCRUM-267/274, docs 07 step 6): location, then SKU or barcode, then the
     * quantity, which must be the whole allocation. The line's hold is deducted in the same transaction
     * ({@code inventory :: consumption}, SCRUM-275). Only the assignee; the same scan again answers 200
     * with the line unchanged. Send an {@code Idempotency-Key} per scan.
     */
    @PostMapping("/tasks/{taskId}/pick-lines/{lineId}/confirmation")
    @RequiresPermission(resource = FulfillmentResources.PICKING, action = Action.UPDATE)
    @Auditable(action = AuditAction.TRANSITION, resourceType = "fulfillment-task", resourceId = "#taskId")
    public ApiResponse<PickLineResponse> confirmPick(@PathVariable UUID taskId, @PathVariable UUID lineId,
            @Valid @RequestBody ConfirmPickRequest request, @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(mapper.toResponse(pickLists.confirm(taskId, lineId, request.locationCode(),
                request.scannedCode(), request.quantity(), actor)));
    }

    /**
     * Goods picked for orders cancelled afterwards, still off their bins (docs 07 BR-05, 17 §4.5 step 15):
     * the put-back screen's queue. Limited to the caller's warehouses; {@code warehouseId} narrows it to one.
     */
    @GetMapping("/put-backs")
    @RequiresPermission(resource = FulfillmentResources.PICKING, action = Action.READ)
    public ApiResponse<PageResponse<PutBackRowResponse>> putBacks(
            @RequestParam(required = false) UUID warehouseId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(required = false) Integer size,
            @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(pickLists.putBacks(warehouseId, page, size == null ? Pages.DEFAULT_PAGE_SIZE : size,
                actor).map(mapper::toResponse));
    }

    /**
     * Puts everything one line picked back onto an active bin of the task's warehouse, as AVAILABLE
     * stock (SCRUM-275). Any warehouse staff member or manager of that warehouse, not only the picker. The
     * same line again answers 200 with the line unchanged.
     */
    @PostMapping("/tasks/{taskId}/pick-lines/{lineId}/put-back")
    @RequiresPermission(resource = FulfillmentResources.PICKING, action = Action.UPDATE)
    @Auditable(action = AuditAction.TRANSITION, resourceType = "fulfillment-task", resourceId = "#taskId")
    public ApiResponse<PickLineResponse> putBack(@PathVariable UUID taskId, @PathVariable UUID lineId,
            @Valid @RequestBody PutBackRequest request, @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(mapper.toResponse(pickLists.putBack(taskId, lineId, request.locationCode(), actor)));
    }

    /**
     * X-SHORT (docs 04c): the assignee is at the line's bin and finds fewer units than allocated - a picking step,
     * so the picking permission. The line turns
     * SHORT, nothing is deducted, and the order goes ON_HOLD ({@code INVENTORY}) for the coordinator. The
     * same report again answers 200 with the line unchanged.
     */
    @PostMapping("/tasks/{taskId}/pick-lines/{lineId}/short")
    @RequiresPermission(resource = FulfillmentResources.PICKING, action = Action.UPDATE)
    @Auditable(action = AuditAction.TRANSITION, resourceType = "fulfillment-task", resourceId = "#taskId")
    public ApiResponse<PickLineResponse> reportShort(@PathVariable UUID taskId, @PathVariable UUID lineId,
            @Valid @RequestBody ShortPickRequest request, @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(mapper.toResponse(pickLists.reportShort(taskId, lineId, request.locationCode(),
                request.foundQuantity(), actor)));
    }

    /**
     * SCRUM-277: the assignee is at the line's bin and finds some of the allocated units damaged - a picking step, so
     * the picking permission. {@code multipart/form-data}: a JSON part {@code report} and one to five image parts
     * {@code photos} (BR-STK-003). The damaged units go to quarantine as DAMAGED; the line gives way to a line for the
     * good units at the same bin and, for the damaged ones, to new lines where other stock replaced them - or a SHORT
     * line, the order ON_HOLD ({@code INVENTORY}) for the coordinator, when none could. The same report again answers
     * 200 with what it made. Retries carry the same {@code requestId}: uploads are not buffered by the idempotency
     * filter.
     */
    @PostMapping(value = "/tasks/{taskId}/pick-lines/{lineId}/damage",
            consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequiresPermission(resource = FulfillmentResources.PICKING, action = Action.UPDATE)
    @Auditable(action = AuditAction.TRANSITION, resourceType = "fulfillment-task", resourceId = "#taskId")
    public ApiResponse<PickDamageResponse> reportDamage(@PathVariable UUID taskId, @PathVariable UUID lineId,
            @Valid @RequestPart("report") PickDamageRequest report,
            @RequestPart(name = "photos", required = false) List<MultipartFile> photos,
            @AuthenticatedUser CurrentUser actor) {
        List<InputStream> opened = new ArrayList<>();
        try {
            List<FileUpload> uploads = new ArrayList<>();
            for (MultipartFile photo : photos == null ? List.<MultipartFile>of() : photos) {
                InputStream content = photo.getInputStream();
                opened.add(content);
                uploads.add(new FileUpload(photo.getOriginalFilename(), photo.getContentType(), photo.getSize(),
                        content));
            }
            return ApiResponse.ok(mapper.toResponse(pickLists.reportDamage(taskId, lineId, new PickLists.PickDamage(
                    report.requestId(), report.locationCode(), report.damagedQuantity(), report.reason(),
                    report.note(), report.damageLocationCode()), uploads, actor)));
        } catch (IOException failure) {
            throw new StorageException("Could not read the multipart upload", failure);
        } finally {
            for (InputStream stream : opened) {
                try {
                    stream.close();
                } catch (IOException ignored) {
                    // A temporary upload that does not close cleanly changes nothing about the report.
                }
            }
        }
    }

    /**
     * Resolves a SHORT line by reallocation (X-SHORT step 04): the same quantity is allocated on other pickable
     * bins of the warehouse, FEFO, never the short one; the answer is the new lines to pick. The order resumes
     * once no line is SHORT. Too little stock is 409 {@code INSUFFICIENT_STOCK} and nothing changes. A decision of
     * whoever assigns the work (the coordinator, the warehouse manager), not of the picker.
     */
    @PostMapping("/tasks/{taskId}/pick-lines/{lineId}/reallocation")
    @RequiresPermission(resource = FulfillmentResources.TASK_ASSIGNMENT, action = Action.UPDATE)
    @Auditable(action = AuditAction.TRANSITION, resourceType = "fulfillment-task", resourceId = "#taskId")
    public ApiResponse<List<PickLineResponse>> reallocateShort(@PathVariable UUID taskId, @PathVariable UUID lineId,
            @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(pickLists.reallocateShort(taskId, lineId, actor).stream().map(mapper::toResponse)
                .toList());
    }

    @GetMapping("/tasks/{taskId}/lines/{lineId}/design-artifacts/{role}/download-url")
    @RequiresPermission(resource = FulfillmentResources.PACKAGES, action = Action.READ)
    public ResponseEntity<ApiResponse<FulfillmentArtifactAccessResponse>> artifactDownload(
            @PathVariable UUID taskId, @PathVariable UUID lineId, @PathVariable String role,
            @AuthenticatedUser CurrentUser actor) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(mapper.toResponse(fulfillment.artifactDownload(taskId, lineId, role, actor))));
    }

    /**
     * Completes packing (G4-5, docs 08 step 7-9): every sealed package not shipped yet becomes one shipment,
     * READY_TO_DISPATCH ({@code shipmentId} in the answer). Without a body, everything the order wants must be packed
     * (409 PACKING_INCOMPLETE); {@code partial} ships what is sealed and leaves the rest for later. A printed design
     * that no longer matches answers 200 with {@code packStatus} ON_HOLD and no shipment. Completed again, the same.
     */
    @PostMapping("/tasks/{taskId}/packing-completion")
    @RequiresPermission(resource = FulfillmentResources.PACKING, action = Action.UPDATE)
    @Auditable(action = AuditAction.TRANSITION, resourceType = "fulfillment-task", resourceId = "#taskId")
    public ApiResponse<FulfillmentTaskResponse> completePacking(@PathVariable UUID taskId,
            @Valid @RequestBody(required = false) PackingCompletionRequest request,
            @AuthenticatedUser CurrentUser actor) {
        PackingCompletion completion = request == null ? PackingCompletion.whole()
                : new PackingCompletion(request.requestId(), request.partial(), request.note());
        return ApiResponse.ok(mapper.toResponse(fulfillment.completePacking(taskId, completion, actor)));
    }

    @PostMapping("/tasks/{taskId}/design-integrity-reverification")
    @RequiresPermission(resource = FulfillmentResources.DESIGN_REVERIFICATION, action = Action.UPDATE)
    @Auditable(action = AuditAction.TRANSITION, resourceType = "fulfillment-task", resourceId = "#taskId")
    public ApiResponse<FulfillmentTaskResponse> reverify(@PathVariable UUID taskId,
            @Valid @RequestBody ReverificationRequest request, @AuthenticatedUser CurrentUser actor) {
        return ApiResponse.ok(mapper.toResponse(fulfillment.reverifyDesignIntegrity(taskId, request.resolutionNote(), actor)));
    }

    record CreateTaskRequest(@NotNull UUID orderId, @NotNull UUID assignedUserId) { }
    record ShortPickRequest(@NotBlank @Size(max = 64) String locationCode,
                            @NotNull @jakarta.validation.constraints.PositiveOrZero Integer foundQuantity) { }
    record AssignmentRequest(@NotNull UUID assignedUserId) { }
    record ReverificationRequest(@NotBlank @Size(max = 1000) String resolutionNote) { }
}
